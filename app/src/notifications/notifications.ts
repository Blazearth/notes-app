import * as Device from 'expo-device';
import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';

import { registerPushToken, unregisterPushToken } from '@/api/client';

// Configure how notifications appear when app is foregrounded
Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowAlert: true,
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
    priority: Notifications.AndroidNotificationPriority.HIGH,
  }),
});

/**
 * Sets up Android notification channels and requests push token from Expo.
 * Saves the token to the backend for the currently authenticated user.
 */
export async function registerForPushNotificationsAsync(): Promise<string | null> {
  if (Platform.OS === 'android') {
    await Notifications.setNotificationChannelAsync('default', {
      name: 'Default',
      importance: Notifications.AndroidImportance.HIGH,
      vibrationPattern: [0, 250, 250, 250],
      lightColor: '#6366F1',
    });

    await Notifications.setNotificationChannelAsync('spaces', {
      name: 'Spaces & Collaboration',
      description: 'Notifications when members join or update shared spaces',
      importance: Notifications.AndroidImportance.HIGH,
      vibrationPattern: [0, 250, 250, 250],
      lightColor: '#6366F1',
    });

    await Notifications.setNotificationChannelAsync('reminders', {
      name: 'Reminders',
      description: 'Time-based reminders for saved notes, recipes and routines',
      importance: Notifications.AndroidImportance.HIGH,
      vibrationPattern: [0, 250, 250, 250],
      lightColor: '#E0A82E',
    });
  }

  if (!Device.isDevice) {
    // Simulator/emulator — push notifications from server won't arrive, but local scheduled reminders still work
    return null;
  }

  const { status: existingStatus } = await Notifications.getPermissionsAsync();
  let finalStatus = existingStatus;

  if (existingStatus !== 'granted') {
    const { status } = await Notifications.requestPermissionsAsync();
    finalStatus = status;
  }

  if (finalStatus !== 'granted') {
    return null;
  }

  try {
    const tokenData = await Notifications.getExpoPushTokenAsync();
    const token = tokenData.data;
    if (token) {
      await registerPushToken(token, Platform.OS).catch((err) => {
        console.warn('Failed to register push token with backend:', err);
      });
    }
    return token;
  } catch (err) {
    console.warn('Could not get Expo push token:', err);
    return null;
  }
}

/**
 * Removes push token registration on sign out.
 */
export async function unregisterPushNotificationsAsync(): Promise<void> {
  try {
    if (!Device.isDevice) return;
    const tokenData = await Notifications.getExpoPushTokenAsync();
    if (tokenData.data) {
      await unregisterPushToken(tokenData.data).catch(() => {});
    }
  } catch {
    // Best-effort cleanup
  }
}

/**
 * Schedules a local time-based reminder for a specific save.
 */
export async function scheduleSaveReminder({
  saveId,
  title,
  body,
  date,
}: {
  saveId: string;
  title: string;
  body?: string;
  date: Date;
}): Promise<string | null> {
  const { status: existingStatus } = await Notifications.getPermissionsAsync();
  let finalStatus = existingStatus;
  if (existingStatus !== 'granted') {
    const { status } = await Notifications.requestPermissionsAsync();
    finalStatus = status;
  }
  if (finalStatus !== 'granted') {
    return null;
  }

  // Cancel any existing reminder for this save before setting a new one
  await cancelSaveReminder(saveId);

  const identifier = await Notifications.scheduleNotificationAsync({
    content: {
      title: `Reminder: ${title}`,
      body: body || 'Tap to view your saved notes.',
      data: { type: 'save', saveId },
      sound: 'default',
    },
    trigger: {
      type: Notifications.SchedulableTriggerInputTypes.DATE,
      date,
    },
  });

  return identifier;
}

/**
 * Cancels any scheduled reminder for a specific save.
 */
export async function cancelSaveReminder(saveId: string): Promise<void> {
  try {
    const scheduled = await Notifications.getAllScheduledNotificationsAsync();
    for (const notif of scheduled) {
      const data = notif.content.data;
      if (data && data.saveId === saveId) {
        await Notifications.cancelScheduledNotificationAsync(notif.identifier);
      }
    }
  } catch (err) {
    console.warn('Failed to cancel reminder for save:', err);
  }
}

/**
 * Checks if there is an active scheduled reminder for a specific save.
 */
export async function getScheduledReminderForSave(
  saveId: string
): Promise<{ identifier: string; triggerDate: Date } | null> {
  try {
    const scheduled = await Notifications.getAllScheduledNotificationsAsync();
    for (const notif of scheduled) {
      const data = notif.content.data;
      if (data && data.saveId === saveId) {
        const trigger = notif.trigger;
        if (trigger && 'value' in trigger && typeof trigger.value === 'number') {
          return {
            identifier: notif.identifier,
            triggerDate: new Date(trigger.value),
          };
        }
        if (trigger && 'date' in trigger && trigger.date) {
          return {
            identifier: notif.identifier,
            triggerDate: new Date(trigger.date as number | string | Date),
          };
        }
        return {
          identifier: notif.identifier,
          triggerDate: new Date(),
        };
      }
    }
  } catch (err) {
    console.warn('Failed to check scheduled reminder:', err);
  }
  return null;
}
