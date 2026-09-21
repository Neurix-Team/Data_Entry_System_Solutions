/* Data Entry System — Web Push service worker.
   Surfaces backend notifications even when the tab is closed. Clicking focuses an
   open window or opens the landing URL the backend picked for the notification. */

self.addEventListener('install', () => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (e) {
    data = { title: 'Data Entry', body: '' };
  }
  const title = (data && data.title) || 'Data Entry';
  const options = {
    body: (data && data.body) || '',
    tag: (data && data.tag) || 'dems-notification',
    icon: '/neurix-mark.png',
    badge: '/neurix-mark.png',
    data: { url: (data && data.url) || '/' },
    renotify: true,
  };
  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || '/';
  event.waitUntil(
    (async () => {
      const clientList = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      for (const client of clientList) {
        try {
          await client.navigate(url);
          return client.focus();
        } catch (e) {
          /* cross-origin window — keep looking */
        }
      }
      return self.clients.openWindow(url);
    })(),
  );
});