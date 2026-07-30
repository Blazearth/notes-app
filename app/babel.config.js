module.exports = function (api) {
  api.cache(true);
  // babel-preset-expo wires up the Reanimated/Worklets transform itself when
  // those packages are installed — adding the plugin by hand double-applies it.
  return { presets: ['babel-preset-expo'] };
};
