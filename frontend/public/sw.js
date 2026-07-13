self.addEventListener('push', (event) => {
  let payload = {
    title: '새 기록이 올라왔어요',
    body: '지웅이 성장일기에 새 기록이 추가됐어요.',
    url: '/',
  }

  if (event.data) {
    try {
      payload = { ...payload, ...event.data.json() }
    } catch {
      payload.body = event.data.text()
    }
  }

  event.waitUntil(self.registration.showNotification(payload.title, {
    body: payload.body,
    icon: '/icon.png',
    badge: '/icon.png',
    data: { url: payload.url || '/' },
  }))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const targetUrl = new URL(event.notification.data?.url || '/', self.location.origin).href

  event.waitUntil((async () => {
    const clientList = await clients.matchAll({ type: 'window', includeUncontrolled: true })
    for (const client of clientList) {
      if (client.url.startsWith(self.location.origin) && 'focus' in client) {
        await client.focus()
        if ('navigate' in client) await client.navigate(targetUrl)
        return
      }
    }
    if (clients.openWindow) await clients.openWindow(targetUrl)
  })())
})