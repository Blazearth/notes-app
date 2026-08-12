const { withAndroidManifest, withAppBuildGradle, withMainApplication, withDangerousMod, AndroidConfig, CodeGenerator } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

/**
 * Adds the Android half of "Capture flow: silent by default" (see CLAUDE.md):
 * a no-display Activity that receives the OS Share Sheet's plain-text intent,
 * durably persists it to a local SQLite queue (`CaptureStore`) *before*
 * confirming success, and hands it to a WorkManager worker for the actual
 * upload. `MainApplication.onCreate` gets a call to `ShareRecovery`, so any
 * capture left incomplete by a process kill, a lost WorkManager enqueue, or a
 * Force-Stop gets re-enqueued on the next cold start. This is a local config
 * plugin (not a native module) so the native sources survive `expo prebuild`
 * instead of requiring android/ to be hand-edited and committed.
 */
const ACTIVITY_NAME = '.share.ShareReceiverActivity';
const TEMPLATE_DIR = path.join(__dirname, 'android-templates', 'share');
const WORKMANAGER_DEP = 'implementation "androidx.work:work-runtime-ktx:2.10.0"';
const MERGE_TAG = 'weavr-share-workmanager';
const RECOVERY_IMPORT_TAG = 'weavr-share-recovery-import';
const RECOVERY_CALL_TAG = 'weavr-share-recovery-call';

function withShareReceiverManifest(config) {
  return withAndroidManifest(config, (config) => {
    const mainApplication = AndroidConfig.Manifest.getMainApplicationOrThrow(config.modResults);
    mainApplication.activity = mainApplication.activity ?? [];

    const alreadyPresent = mainApplication.activity.some(
      (activity) => activity.$['android:name'] === ACTIVITY_NAME,
    );
    if (!alreadyPresent) {
      mainApplication.activity.push({
        $: {
          'android:name': ACTIVITY_NAME,
          'android:exported': 'true',
          'android:theme': '@android:style/Theme.NoDisplay',
          'android:noHistory': 'true',
          'android:excludeFromRecents': 'true',
        },
        'intent-filter': [
          {
            action: [{ $: { 'android:name': 'android.intent.action.SEND' } }],
            category: [{ $: { 'android:name': 'android.intent.category.DEFAULT' } }],
            data: [{ $: { 'android:mimeType': 'text/plain' } }],
          },
          {
            action: [{ $: { 'android:name': 'android.intent.action.SEND' } }],
            category: [{ $: { 'android:name': 'android.intent.category.DEFAULT' } }],
            data: [{ $: { 'android:mimeType': 'image/*' } }],
          },
        ],
      });
    }
    return config;
  });
}

function withShareReceiverGradleDep(config) {
  return withAppBuildGradle(config, (config) => {
    if (config.modResults.language !== 'groovy') {
      throw new Error('withAndroidShareReceiver expects a Groovy android/app/build.gradle');
    }
    if (!config.modResults.contents.includes('androidx.work:work-runtime-ktx')) {
      config.modResults.contents = CodeGenerator.mergeContents({
        src: config.modResults.contents,
        newSrc: `    ${WORKMANAGER_DEP}`,
        tag: MERGE_TAG,
        anchor: /dependencies\s*{/,
        offset: 1,
        comment: '//',
      }).contents;
    }
    return config;
  });
}

function withShareReceiverSources(config) {
  return withDangerousMod(config, [
    'android',
    async (config) => {
      const packageName = config.android?.package;
      if (!packageName) {
        throw new Error('withAndroidShareReceiver requires android.package to be set in app.json');
      }
      const packagePath = packageName.split('.').join(path.sep);
      const targetDir = path.join(
        config.modRequest.platformProjectRoot,
        'app',
        'src',
        'main',
        'java',
        packagePath,
        'share',
      );
      fs.mkdirSync(targetDir, { recursive: true });

      for (const fileName of fs.readdirSync(TEMPLATE_DIR)) {
        const template = fs.readFileSync(path.join(TEMPLATE_DIR, fileName), 'utf8');
        const contents = template.split('{{PACKAGE}}').join(packageName);
        fs.writeFileSync(path.join(targetDir, fileName), contents, 'utf8');
      }

      return config;
    },
  ]);
}

function withShareReceiverRecovery(config) {
  return withMainApplication(config, (config) => {
    const packageName = config.android?.package;
    if (!packageName) {
      throw new Error('withAndroidShareReceiver requires android.package to be set in app.json');
    }
    if (config.modResults.language !== 'kt') {
      throw new Error('withAndroidShareReceiver expects a Kotlin MainApplication.kt');
    }

    let { contents } = config.modResults;

    if (!contents.includes(`${packageName}.share.ShareRecovery`)) {
      contents = CodeGenerator.mergeContents({
        src: contents,
        newSrc: `import ${packageName}.share.ShareRecovery`,
        tag: RECOVERY_IMPORT_TAG,
        anchor: /^import /m,
        offset: 0,
        comment: '//',
      }).contents;
    }

    if (!contents.includes('ShareRecovery.recoverPendingCaptures')) {
      contents = CodeGenerator.mergeContents({
        src: contents,
        newSrc: '    ShareRecovery.recoverPendingCaptures(this)',
        tag: RECOVERY_CALL_TAG,
        anchor: /super\.onCreate\(\)/,
        offset: 1,
        comment: '//',
      }).contents;
    }

    config.modResults.contents = contents;
    return config;
  });
}

module.exports = function withAndroidShareReceiver(config) {
  config = withShareReceiverManifest(config);
  config = withShareReceiverGradleDep(config);
  config = withShareReceiverSources(config);
  config = withShareReceiverRecovery(config);
  return config;
};
