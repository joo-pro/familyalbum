<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import appIconUrl from './assets/icon.png'

const defaultConfig = {
  appTitle: '지웅이 성장일기',
}

const appConfig = ref(defaultConfig)
const assets = ref([])
const selectedFiles = ref([])
const isUploading = ref(false)
const uploadMessage = ref('')
const uploadVisibility = ref('FAMILY')
const uploadProgress = ref(0)
const uploadLoadedBytes = ref(0)
const currentUploadIndex = ref(0)
const activeAsset = ref(null)
const isSelectionMode = ref(false)
const selectedAssetIds = ref(new Set())
const isDragSelecting = ref(false)
const isActionMenuOpen = ref(false)
const isDetailMenuOpen = ref(false)
const isDetailInfoOpen = ref(false)
const toastMessage = ref('')
const touchStartX = ref(null)
const fileInput = ref(null)
const pageSentinel = ref(null)
const nextCursor = ref(null)
const isLoadingAssets = ref(false)
const assetLoadMessage = ref('')
const hasMoreAssets = ref(true)
const visibleThumbnailIds = ref(new Set())
const session = ref({ loading: true, authenticated: false, approved: false, admin: false, user: null })
const isAdminPanelOpen = ref(false)
const adminUsers = ref([])
const isLoadingAdminUsers = ref(false)
const pushState = ref('checking')
const isPushLoading = ref(false)

let loadMoreObserver
let thumbnailObserver
let toastTimer
let previousBodyOverflow = ''
let serviceWorkerRegistration
let dragSelectionState = null
let suppressNextAssetClick = false
const totalSize = computed(() => selectedFiles.value.reduce((sum, file) => sum + file.size, 0))
const currentUser = computed(() => session.value.user)
const canAccessAlbum = computed(() => session.value.authenticated && session.value.approved)
const isAdmin = computed(() => session.value.admin)
const canManageMedia = computed(() => isAdmin.value)
const uploadProgressPercent = computed(() => Math.min(100, Math.round(uploadProgress.value)))
const uploadStatusText = computed(() => {
  if (!isUploading.value) return selectedFiles.value.length ? `${selectedFiles.value.length}개 선택됨` : '업로드 상태'
  return `${currentUploadIndex.value}/${selectedFiles.value.length}개 업로드 중`
})
const selectedCount = computed(() => selectedAssetIds.value.size)
const selectedAssetIdList = computed(() => Array.from(selectedAssetIds.value))
const eagerThumbnailIds = computed(() => new Set(assets.value.slice(0, 30).map((asset) => asset.id)))
const activeIndex = computed(() => assets.value.findIndex((asset) => asset.id === activeAsset.value?.id))
const hasPreviousAsset = computed(() => activeIndex.value > 0)
const hasNextAsset = computed(() => activeIndex.value >= 0 && activeIndex.value < assets.value.length - 1)
const isMobileDevice = computed(() => {
  if (typeof navigator === 'undefined') return false
  return /Android|iPhone|iPad|iPod/i.test(navigator.userAgent) || navigator.maxTouchPoints > 1
})
const downloadActionLabel = computed(() => (isMobileDevice.value ? '공유/저장' : '다운로드'))
const uploadButtonLabel = computed(() => {
  if (isUploading.value) return '업로드 중'
  if (selectedFiles.value.length > 0) return `${selectedFiles.value.length}개 업로드`
  return '업로드'
})
const canUseNotifications = computed(() => typeof window !== 'undefined'
  && 'serviceWorker' in navigator
  && 'PushManager' in window
  && 'Notification' in window)
const pushButtonLabel = computed(() => {
  if (isPushLoading.value) return '알림 설정 중'
  if (!canUseNotifications.value) return '알림 미지원'
  if (pushState.value === 'subscribed') return '알림 켜짐'
  if (pushState.value === 'blocked') return '알림 차단됨'
  if (pushState.value === 'server-disabled') return '알림 서버 설정 필요'
  return '새 기록 알림 켜기'
})
const timelineGroups = computed(() => {
  const groups = new Map()
  for (const asset of assets.value) {
    const key = dayKey(assetDate(asset))
    if (!groups.has(key)) {
      groups.set(key, {
        key,
        label: formatDayLabel(assetDate(asset)),
        assets: [],
      })
    }
    groups.get(key).assets.push(asset)
  }
  return Array.from(groups.values())
})

onMounted(async () => {
  await Promise.all([loadAppConfig(), loadSession()])
  if (canAccessAlbum.value) {
    await Promise.all([loadAssets(), initializePushNotifications()])
  }
  await nextTick()
  setupLoadMoreObserver()
})

onBeforeUnmount(() => {
  loadMoreObserver?.disconnect()
  thumbnailObserver?.disconnect()
})

async function loadAppConfig() {
  try {
    const response = await fetch('/app-config.json', { cache: 'no-store' })
    if (!response.ok) return

    const config = await response.json()
    appConfig.value = { ...defaultConfig, ...config }
    document.title = appConfig.value.appTitle
  } catch {
    document.title = defaultConfig.appTitle
  }
}



async function loadSession() {
  session.value = { ...session.value, loading: true }
  try {
    const response = await fetch('/api/auth/me', { cache: 'no-store', credentials: 'same-origin' })
    if (!response.ok) throw new Error('AUTH_SESSION_' + response.status)
    session.value = { loading: false, ...await response.json() }
  } catch {
    session.value = { loading: false, authenticated: false, approved: false, admin: false, user: null }
  }
}

function loginWithKakao() {
  window.location.href = '/oauth2/authorization/kakao'
}

async function resetLoginState() {
  await logout({ silent: true })
  window.location.href = '/oauth2/authorization/kakao'
}

async function logout(options = {}) {
  await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' })
  session.value = { loading: false, authenticated: false, approved: false, admin: false, user: null }
  assets.value = []
  activeAsset.value = null
  isAdminPanelOpen.value = false
  if (!options.silent) showToast('로그아웃했어요.')
}

async function openAdminPanel() {
  isActionMenuOpen.value = false
  isAdminPanelOpen.value = true
  await loadAdminUsers()
}

async function loadAdminUsers() {
  if (!isAdmin.value) return
  isLoadingAdminUsers.value = true
  try {
    const response = await fetch('/api/admin/users', { cache: 'no-store' })
    if (!response.ok) throw new Error('사용자 목록을 불러오지 못했어요.')
    const payload = await response.json()
    adminUsers.value = payload.users ?? []
  } catch (error) {
    showToast(error.message)
  } finally {
    isLoadingAdminUsers.value = false
  }
}

async function updateUserRole(user, role) {
  try {
    const response = await fetch(`/api/admin/users/${user.id}/role`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ role }),
    })
    if (!response.ok) throw new Error('권한을 변경하지 못했어요.')
    await loadAdminUsers()
    showToast('권한을 변경했어요.')
  } catch (error) {
    showToast(error.message)
  }
}

function roleLabel(role) {
  if (role === 'MOTHER') return '엄마'
  if (role === 'FATHER') return '아빠'
  if (role === 'FAMILY') return '가족'
  return '승인 대기'
}

function visibilityLabel(visibility) {
  if (visibility === 'PARENTS') return '부모 공개'
  return '가족 공개'
}

function uploaderRoleLabel(role) {
  if (role === 'MOTHER') return '엄마'
  if (role === 'FATHER') return '아빠'
  if (role === 'FAMILY') return '가족'
  return '알 수 없음'
}
async function initializePushNotifications() {
  if (!canUseNotifications.value) {
    pushState.value = 'unsupported'
    return
  }

  if (Notification.permission === 'denied') {
    pushState.value = 'blocked'
    return
  }

  try {
    serviceWorkerRegistration = await navigator.serviceWorker.register('/sw.js')
    const subscription = await serviceWorkerRegistration.pushManager.getSubscription()
    pushState.value = subscription ? 'subscribed' : 'default'
    if (subscription) await savePushSubscription(subscription)
  } catch {
    pushState.value = 'unsupported'
  }
}

async function enablePushNotifications() {
  isActionMenuOpen.value = false
  if (!canAccessAlbum.value) return
  if (!canUseNotifications.value) {
    showToast('이 브라우저에서는 알림을 사용할 수 없어요.')
    return
  }
  if (Notification.permission === 'denied') {
    pushState.value = 'blocked'
    showToast('브라우저 설정에서 알림 차단을 해제해 주세요.')
    return
  }

  isPushLoading.value = true
  try {
    const permission = Notification.permission === 'granted'
      ? 'granted'
      : await Notification.requestPermission()
    if (permission !== 'granted') {
      pushState.value = permission === 'denied' ? 'blocked' : 'default'
      showToast('알림 권한이 허용되지 않았어요.')
      return
    }

    const keyResponse = await fetch('/api/push/public-key', { cache: 'no-store' })
    if (!keyResponse.ok) throw new Error('알림 설정을 불러오지 못했어요.')
    const keyPayload = await keyResponse.json()
    if (!keyPayload.enabled || !keyPayload.publicKey) {
      pushState.value = 'server-disabled'
      showToast('서버 알림 키 설정이 필요해요.')
      return
    }

    serviceWorkerRegistration = serviceWorkerRegistration || await navigator.serviceWorker.register('/sw.js')
    let subscription = await serviceWorkerRegistration.pushManager.getSubscription()
    if (!subscription) {
      subscription = await serviceWorkerRegistration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(keyPayload.publicKey),
      })
    }
    await savePushSubscription(subscription)
    pushState.value = 'subscribed'
    showToast('새 기록 알림을 켰어요.')
  } catch (error) {
    showToast(error.message || '알림 설정에 실패했어요.')
  } finally {
    isPushLoading.value = false
  }
}

async function savePushSubscription(subscription) {
  const payload = subscription.toJSON()
  const response = await fetch('/api/push/subscriptions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!response.ok) throw new Error('알림 구독을 저장하지 못했어요.')
}

function urlBase64ToUint8Array(value) {
  const padding = '='.repeat((4 - value.length % 4) % 4)
  const base64 = (value + padding).replace(/-/g, '+').replace(/_/g, '/')
  const raw = window.atob(base64)
  const output = new Uint8Array(raw.length)
  for (let index = 0; index < raw.length; index += 1) {
    output[index] = raw.charCodeAt(index)
  }
  return output
}
async function loadAssets() {
  if (!canAccessAlbum.value) return
  assets.value = []
  nextCursor.value = null
  hasMoreAssets.value = true
  assetLoadMessage.value = ''
  visibleThumbnailIds.value = new Set()
  thumbnailObserver?.disconnect()
  thumbnailObserver = null
  await loadNextAssets()
}

async function loadNextAssets() {
  if (isLoadingAssets.value || !hasMoreAssets.value) return
  isLoadingAssets.value = true
  try {
    const params = new URLSearchParams({ limit: '48' })
    if (nextCursor.value) params.set('cursor', nextCursor.value)
    const response = await fetch(`/api/media?${params}`)
    if (!response.ok) {
      throw new Error(`MEDIA_LIST_${response.status}`)
    }

    const page = await response.json()
    const items = Array.isArray(page) ? page : (page.items ?? [])
    assets.value = nextCursor.value ? [...assets.value, ...items] : items
    nextCursor.value = Array.isArray(page) ? null : (page.nextCursor ?? null)
    hasMoreAssets.value = Array.isArray(page) ? false : Boolean(page.hasMore)
    assetLoadMessage.value = ''
    await nextTick()
    setupLoadMoreObserver()
  } catch {
    if (!nextCursor.value) assets.value = []
    hasMoreAssets.value = false
    assetLoadMessage.value = '사진과 동영상을 불러오지 못했어요. 잠시 후 새로고침해 주세요.'
  } finally {
    isLoadingAssets.value = false
  }
}

function setupLoadMoreObserver() {
  if (!pageSentinel.value) return
  loadMoreObserver?.disconnect()
  loadMoreObserver = new IntersectionObserver((entries) => {
    if (entries.some((entry) => entry.isIntersecting)) {
      loadNextAssets()
    }
  }, { rootMargin: '720px 0px' })
  loadMoreObserver.observe(pageSentinel.value)
}

function ensureThumbnailObserver() {
  if (thumbnailObserver) return thumbnailObserver
  thumbnailObserver = new IntersectionObserver((entries) => {
    const next = new Set(visibleThumbnailIds.value)
    for (const entry of entries) {
      if (!entry.isIntersecting) continue
      const assetId = entry.target.dataset.assetId
      if (assetId) next.add(assetId)
      thumbnailObserver.unobserve(entry.target)
    }
    visibleThumbnailIds.value = next
  }, { rootMargin: '520px 0px' })
  return thumbnailObserver
}

const vLazyThumbnail = {
  mounted(el, binding) {
    el.dataset.assetId = binding.value
    ensureThumbnailObserver().observe(el)
  },
  updated(el, binding) {
    if (el.dataset.assetId === binding.value) return
    el.dataset.assetId = binding.value
    if (!visibleThumbnailIds.value.has(binding.value)) {
      ensureThumbnailObserver().observe(el)
    }
  },
  unmounted(el) {
    thumbnailObserver?.unobserve(el)
  },
}

function isThumbnailVisible(assetId) {
  return eagerThumbnailIds.value.has(assetId) || visibleThumbnailIds.value.has(assetId)
}

function thumbnailLoadingMode(assetId) {
  return eagerThumbnailIds.value.has(assetId) ? 'eager' : 'lazy'
}

function thumbnailFetchPriority(assetId) {
  return eagerThumbnailIds.value.has(assetId) ? 'high' : 'auto'
}
function openFilePicker() {
  fileInput.value?.click()
  isActionMenuOpen.value = false
}

function onFileChange(event) {
  selectedFiles.value = Array.from(event.target.files ?? [])
  uploadMessage.value = ''
  uploadProgress.value = 0
  uploadLoadedBytes.value = 0
  currentUploadIndex.value = 0
}

async function uploadSelectedFiles() {
  if (selectedFiles.value.length === 0) return

  isActionMenuOpen.value = false
  isUploading.value = true
  uploadMessage.value = '업로드를 준비하고 있어요.'
  uploadProgress.value = 0
  uploadLoadedBytes.value = 0
  currentUploadIndex.value = 0

  try {
    let uploadedCount = 0
    let duplicateCount = 0
    let completedBytes = 0

    for (const [index, file] of selectedFiles.value.entries()) {
      currentUploadIndex.value = index + 1
      uploadMessage.value = `${file.name} 업로드 중이에요.`

      const result = await uploadFileWithProgress(file, (loaded) => {
        uploadLoadedBytes.value = completedBytes + loaded
        uploadProgress.value = totalSize.value ? (uploadLoadedBytes.value / totalSize.value) * 100 : 0
      })
      completedBytes += file.size
      uploadLoadedBytes.value = completedBytes
      uploadProgress.value = totalSize.value ? (completedBytes / totalSize.value) * 100 : 100

      if (result.duplicate) duplicateCount += 1
      else uploadedCount += 1
    }

    uploadProgress.value = 100
    uploadMessage.value = duplicateCount > 0
      ? `업로드 완료: 새 파일 ${uploadedCount}개, 중복 ${duplicateCount}개 건너뜀`
      : '업로드가 완료됐어요.'
    showToast(uploadMessage.value)
    selectedFiles.value = []
    if (fileInput.value) fileInput.value.value = ''
    await loadAssets()
  } catch (error) {
    uploadMessage.value = error.message
    showToast(error.message)
  } finally {
    isUploading.value = false
  }
}

function uploadFileWithProgress(file, onProgress) {
  return new Promise((resolve, reject) => {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('capturedAt', new Date(file.lastModified).toISOString())
    formData.append('visibility', uploadVisibility.value)

    const request = new XMLHttpRequest()
    request.open('POST', '/api/media/upload')
    request.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(event.loaded)
    }
    request.onload = () => {
      if (request.status < 200 || request.status >= 300) {
        reject(new Error(request.responseText || '업로드에 실패했어요.'))
        return
      }
      try {
        resolve(JSON.parse(request.responseText))
      } catch {
        reject(new Error('업로드 응답을 읽지 못했어요.'))
      }
    }
    request.onerror = () => reject(new Error('네트워크 문제로 업로드하지 못했어요.'))
    request.send(formData)
  })
}
function handleAssetClick(event, asset) {
  if (suppressNextAssetClick) {
    suppressNextAssetClick = false
    return
  }
  if (isSelectionMode.value) {
    if (event.target.closest('.select-badge')) {
      toggleAssetSelection(asset.id)
    }
    return
  }
  openAsset(asset)
}

function handleAssetPointerDown(event, asset) {
  if (!isSelectionMode.value || event.button > 0) return
  if (!event.target.closest('.select-badge')) return
  if (event.pointerType === 'mouse' && event.buttons !== 1) return
  event.preventDefault()
  suppressNextAssetClick = true
  dragSelectionState = {
    pointerId: event.pointerId,
    startAssetId: asset.id,
    selected: !isSelected(asset.id),
    baseSelection: new Set(selectedAssetIds.value),
  }
  isDragSelecting.value = true
  event.currentTarget.setPointerCapture?.(event.pointerId)
  applyDragSelectionRange(asset.id)
}

function handleAssetPointerMove(event) {
  if (!dragSelectionState || dragSelectionState.pointerId !== event.pointerId) return
  event.preventDefault()
  autoScrollDuringDrag(event.clientY)
  const target = document.elementFromPoint(event.clientX, event.clientY)?.closest?.('[data-asset-id]')
  const assetId = target?.dataset?.assetId
  if (assetId) applyDragSelectionRange(assetId)
}

function handleAssetPointerEnd(event) {
  if (!dragSelectionState || dragSelectionState.pointerId !== event.pointerId) return
  event.preventDefault()
  dragSelectionState = null
  isDragSelecting.value = false
  window.setTimeout(() => {
    suppressNextAssetClick = false
  }, 350)
}

function applyDragSelectionRange(currentAssetId) {
  if (!dragSelectionState) return
  const rangeIds = dragRangeAssetIds(dragSelectionState.startAssetId, currentAssetId)
  const next = new Set(dragSelectionState.baseSelection)
  for (const assetId of rangeIds) {
    if (dragSelectionState.selected) {
      next.add(assetId)
    } else {
      next.delete(assetId)
    }
  }
  selectedAssetIds.value = next
}

function dragRangeAssetIds(startAssetId, currentAssetId) {
  const startCard = findAssetCard(startAssetId)
  const currentCard = findAssetCard(currentAssetId)
  if (!startCard || !currentCard) return [currentAssetId]

  const startRect = startCard.getBoundingClientRect()
  const currentRect = currentCard.getBoundingClientRect()
  const left = Math.min(startRect.left, currentRect.left) - 1
  const right = Math.max(startRect.right, currentRect.right) + 1
  const top = Math.min(startRect.top, currentRect.top) - 1
  const bottom = Math.max(startRect.bottom, currentRect.bottom) + 1

  return assetCards()
    .filter((card) => {
      const rect = card.getBoundingClientRect()
      const centerX = rect.left + rect.width / 2
      const centerY = rect.top + rect.height / 2
      return centerX >= left && centerX <= right && centerY >= top && centerY <= bottom
    })
    .map((card) => card.dataset.assetId)
    .filter(Boolean)
}

function findAssetCard(assetId) {
  return assetCards().find((card) => card.dataset.assetId === assetId)
}

function assetCards() {
  return Array.from(document.querySelectorAll('.asset-card[data-asset-id]'))
}

function autoScrollDuringDrag(clientY) {
  const edgeSize = 88
  const maxStep = 18
  if (clientY < edgeSize) {
    window.scrollBy({ top: -maxStep, behavior: 'auto' })
    return
  }
  if (clientY > window.innerHeight - edgeSize) {
    window.scrollBy({ top: maxStep, behavior: 'auto' })
  }
}

function openAsset(asset) {
  activeAsset.value = asset
  isDetailMenuOpen.value = false
  isDetailInfoOpen.value = false
  lockBackground()
}

function closeAsset() {
  activeAsset.value = null
  isDetailMenuOpen.value = false
  isDetailInfoOpen.value = false
  unlockBackground()
}

function lockBackground() {
  previousBodyOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
}

function unlockBackground() {
  document.body.style.overflow = previousBodyOverflow
}
function handleDetailPanelClick(event) {
  if (!isDetailInfoOpen.value && !isDetailMenuOpen.value) return
  if (event.target.closest('.detail-info') || event.target.closest('.detail-quick-actions')) return
  isDetailInfoOpen.value = false
  isDetailMenuOpen.value = false
}
function handleDetailBackdropClick() {
  if (isDetailInfoOpen.value || isDetailMenuOpen.value) {
    isDetailInfoOpen.value = false
    isDetailMenuOpen.value = false
    return
  }
  closeAsset()
}
function toggleDetailInfo() {
  isDetailInfoOpen.value = !isDetailInfoOpen.value
  isDetailMenuOpen.value = false
}

function onDetailTouchStart(event) {
  touchStartX.value = event.changedTouches?.[0]?.clientX ?? null
}

function onDetailTouchEnd(event) {
  if (touchStartX.value == null) return
  const endX = event.changedTouches?.[0]?.clientX ?? touchStartX.value
  const deltaX = endX - touchStartX.value
  touchStartX.value = null
  if (Math.abs(deltaX) < 48) return
  if (deltaX < 0) {
    showNextAsset()
  } else {
    showPreviousAsset()
  }
}

function showPreviousAsset() {
  if (!hasPreviousAsset.value) return
  activeAsset.value = assets.value[activeIndex.value - 1]
  isDetailMenuOpen.value = false
  isDetailInfoOpen.value = false
}

function showNextAsset() {
  if (!hasNextAsset.value) return
  activeAsset.value = assets.value[activeIndex.value + 1]
  isDetailMenuOpen.value = false
  isDetailInfoOpen.value = false
}

function toggleSelectionMode() {
  isSelectionMode.value = !isSelectionMode.value
  isActionMenuOpen.value = false
  if (!isSelectionMode.value) {
    clearSelection()
    dragSelectionState = null
    isDragSelecting.value = false
    suppressNextAssetClick = false
  }
}

function isSelected(assetId) {
  return selectedAssetIds.value.has(assetId)
}

function toggleAssetSelection(assetId) {
  const next = new Set(selectedAssetIds.value)
  if (next.has(assetId)) {
    next.delete(assetId)
  } else {
    next.add(assetId)
  }
  selectedAssetIds.value = next
  if (next.size > 0) {
    isSelectionMode.value = true
  }
}

function clearSelection() {
  selectedAssetIds.value = new Set()
}

async function refreshAssets() {
  isActionMenuOpen.value = false
  await loadAssets()
}

async function downloadAsset(asset) {
  isDetailMenuOpen.value = false
  try {
    if (isMobileDevice.value) {
      await shareAssets([asset])
      return
    }

    const response = await fetch(`/api/media/${asset.id}/download-url`, { method: 'POST' })
    if (!response.ok) throw new Error('다운로드 링크를 만들지 못했어요.')
    const payload = await response.json()
    window.location.href = payload.downloadUrl
    showToast('다운로드를 시작했어요.')
  } catch (error) {
    showToast(error.message)
    throw error
  }
}
async function downloadSelectedAssets() {
  if (selectedCount.value === 0) return
  isActionMenuOpen.value = false
  try {
    const selectedAssets = assets.value.filter((asset) => selectedAssetIds.value.has(asset.id))

    if (isMobileDevice.value) {
      await shareAssets(selectedAssets)
      return
    }

    const response = await fetch('/api/media/download', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ assetIds: selectedAssetIdList.value }),
    })
    if (!response.ok) throw new Error('선택한 파일을 다운로드하지 못했어요.')
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'familyalbum-media.zip'
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    showToast('선택한 파일 다운로드를 시작했어요.')
  } catch (error) {
    showToast(error.message)
    throw error
  }
}

async function shareAssets(targetAssets) {
  const files = []
  for (const asset of targetAssets) {
    const response = await fetch(`/api/media/${asset.id}/file`)
    if (!response.ok) throw new Error('공유할 파일을 준비하지 못했어요.')
    const blob = await response.blob()
    files.push(new File([blob], asset.filename, { type: asset.contentType }))
  }

  if (navigator.canShare?.({ files })) {
    await navigator.share({
      files,
      title: appConfig.value.appTitle,
      text: 'FamilyAlbum에서 저장할 사진과 동영상이에요.',
    })
    showToast('공유 화면을 열었어요.')
    return
  }

  if (files.length === 1) {
    const url = URL.createObjectURL(files[0])
    window.open(url, '_blank', 'noopener')
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
    showToast('파일을 열었어요.')
    return
  }

  throw new Error('이 브라우저에서는 여러 파일을 사진첩으로 공유할 수 없어요. 한 개씩 저장해 주세요.')
}

async function deleteAsset(asset) {
  isDetailMenuOpen.value = false
  if (!confirm(`${asset.filename} 파일을 삭제할까요?`)) return
  const response = await fetch(`/api/media/${asset.id}`, { method: 'DELETE' })
  if (!response.ok) throw new Error('삭제하지 못했어요.')
  closeAsset()
  await loadAssets()
}

async function updateSelectedVisibility(visibility) {
  if (!canManageMedia.value || selectedCount.value === 0) return
  isActionMenuOpen.value = false
  const response = await fetch('/api/media/visibility', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ assetIds: selectedAssetIdList.value, visibility }),
  })
  if (!response.ok) throw new Error('공개 범위를 변경하지 못했어요.')
  const label = visibilityLabel(visibility)
  clearSelection()
  isSelectionMode.value = false
  await loadAssets()
  showToast(`${label}로 변경했어요.`)
}
async function deleteSelectedAssets() {
  if (selectedCount.value === 0) return
  if (!confirm(`선택한 ${selectedCount.value}개 파일을 삭제할까요?`)) return
  isActionMenuOpen.value = false
  const response = await fetch('/api/media/delete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ assetIds: selectedAssetIdList.value }),
  })
  if (!response.ok) throw new Error('선택한 파일을 삭제하지 못했어요.')
  clearSelection()
  isSelectionMode.value = false
  await loadAssets()
}

function showToast(message) {
  toastMessage.value = message
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => {
    toastMessage.value = ''
  }, 2600)
}
function mediaViewUrl(asset) {
  return `/api/media/${asset.id}/view`
}

function mediaThumbnailUrl(asset) {
  return `/api/media/${asset.id}/thumbnail`
}

function assetDate(asset) {
  return asset.capturedAt || asset.createdAt
}

function dayKey(value) {
  if (!value) return 'unknown'
  const date = new Date(value)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function formatDayLabel(value) {
  if (!value) return '날짜 미정'
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  }).format(new Date(value))
}

function formatDateTime(value) {
  if (!value) return '날짜 미정'
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function formatBytes(bytes) {
  if (!bytes) return '0 KB'
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`
}
</script>

<template>
  <main class="app-shell">
    <section class="hero-section">
      <div class="hero-sky" aria-hidden="true">
        <span></span>
        <span></span>
        <span></span>
      </div>
      <div class="hero-grid">
        <div class="hero-copy">
          <div class="hero-brand">
            <div class="hero-icon-frame">
              <img class="hero-icon" :src="appIconUrl" alt="" aria-hidden="true" />
            </div>
            <div class="hero-title-block">
              <span class="hero-kicker">Family Album</span>
              <h1>{{ appConfig.appTitle }}</h1>
              <div class="hero-title-lines" aria-hidden="true">
                <span></span>
                <span></span>
                <span></span>
              </div>
            </div>
          </div>
        </div>
        <div class="hero-memory-board" aria-hidden="true">
          <div class="memory-card memory-card-main">
            <span class="memory-tape"></span>
            <img :src="appIconUrl" alt="" />
          </div>
          <div class="memory-card memory-card-soft"></div>
          <div class="memory-card memory-card-note">
            <span></span>
            <span></span>
          </div>
          <div class="memory-sticker memory-sticker-star">✦</div>
          <div class="memory-sticker memory-sticker-heart">♡</div>
        </div>
      </div>
    </section>

    <section v-if="session.loading" class="auth-card">
      <div class="auth-visual">
        <img :src="appIconUrl" alt="" aria-hidden="true" />
      </div>
      <div>
        <span class="hero-kicker">Private Album</span>
        <h2>가족 앨범을 준비하고 있어요</h2>
        <p>잠시만 기다려 주세요.</p>
      </div>
    </section>

    <section v-else-if="!session.authenticated" class="auth-card">
      <div class="auth-visual">
        <img :src="appIconUrl" alt="" aria-hidden="true" />
      </div>
      <div>
        <span class="hero-kicker">Private Album</span>
        <h2>가족만 볼 수 있는 성장일기예요</h2>
        <p>카카오톡으로 로그인한 뒤 관리자의 승인을 받으면 사진과 동영상을 볼 수 있어요.</p>
      </div>
      <button class="kakao-login-button" type="button" @click="loginWithKakao">카카오톡으로 로그인</button>
      <button class="secondary-action" type="button" @click="resetLoginState">로그인 상태 초기화</button>
    </section>

    <section v-else-if="!session.approved" class="auth-card">
      <div class="auth-visual">
        <img v-if="currentUser?.profileImageUrl" :src="currentUser.profileImageUrl" alt="" />
        <img v-else :src="appIconUrl" alt="" aria-hidden="true" />
      </div>
      <div>
        <span class="hero-kicker">Approval Required</span>
        <h2>관리자 승인을 기다리고 있어요</h2>
        <p>{{ currentUser?.nickname }}님으로 로그인했어요. 관리자가 가족 권한을 부여하면 앨범이 열립니다.</p>
        <small>카카오 ID: {{ currentUser?.kakaoId }}</small>
      </div>
      <button class="secondary-action" type="button" @click="logout">로그아웃</button>
    </section>
    <input v-if="canAccessAlbum && canManageMedia" ref="fileInput" class="hidden-file-input" type="file" multiple accept="image/*,video/*,.heic,.heif,.heics,.heifs,.mov,.m4v,image/heic,image/heif,video/quicktime" @change="onFileChange" />

    <section v-if="canAccessAlbum && canManageMedia && (selectedFiles.length || uploadMessage)" class="upload-summary" aria-live="polite">
      <div class="upload-summary-main">
        <div>
          <strong>{{ uploadStatusText }}</strong>
          <span>{{ selectedFiles.length ? formatBytes(totalSize) : uploadMessage }}</span>
        </div>
        <div v-if="selectedFiles.length" class="upload-visibility" role="radiogroup" aria-label="공개 범위">
          <button type="button" :class="{ active: uploadVisibility === 'FAMILY' }" @click="uploadVisibility = 'FAMILY'">가족 공개</button>
          <button type="button" :class="{ active: uploadVisibility === 'PARENTS' }" @click="uploadVisibility = 'PARENTS'">부모만</button>
        </div>
        <button class="upload-inline-button" type="button" :disabled="!selectedFiles.length || isUploading" @click="uploadSelectedFiles">
          {{ uploadButtonLabel }}
        </button>
      </div>
      <div v-if="isUploading || uploadProgress > 0" class="upload-progress" role="progressbar" :aria-valuenow="uploadProgressPercent" aria-valuemin="0" aria-valuemax="100">
        <div class="upload-progress-track"><span :style="{ width: `${uploadProgressPercent}%` }"></span></div>
        <strong>{{ uploadProgressPercent }}%</strong>
      </div>
      <p v-if="uploadMessage">{{ uploadMessage }}</p>
    </section>

    <section v-if="canAccessAlbum" class="content-section">
      <div v-if="isSelectionMode" class="selection-toolbar">
        <div>
          <strong>{{ selectedCount }}개 선택됨</strong>
          <span>필요한 사진과 동영상을 고른 뒤 바로 처리할 수 있어요.</span>
        </div>
        <div class="selection-actions">
          <button type="button" @click="toggleSelectionMode">선택 취소</button>
          <button type="button" :disabled="selectedCount === 0" @click="downloadSelectedAssets">{{ downloadActionLabel }}</button>
          <button v-if="canManageMedia" type="button" :disabled="selectedCount === 0" @click="updateSelectedVisibility('FAMILY')">가족 공개</button>
          <button v-if="canManageMedia" type="button" :disabled="selectedCount === 0" @click="updateSelectedVisibility('PARENTS')">부모만 공개</button>
          <button v-if="canManageMedia" type="button" :disabled="selectedCount === 0" class="danger-button" @click="deleteSelectedAssets">삭제</button>
        </div>
      </div>

      <div v-if="assetLoadMessage && assets.length === 0" class="empty-state">
        <div class="empty-visual" aria-hidden="true">!</div>
        <h3>기록을 불러오지 못했어요</h3>
        <p>{{ assetLoadMessage }}</p>
      </div>

      <div v-else-if="isLoadingAssets && assets.length === 0" class="empty-state">
        <div class="empty-visual" aria-hidden="true">...</div>
        <h3>기록을 불러오는 중이에요</h3>
        <p>잠시만 기다려 주세요.</p>
      </div>

      <div v-else-if="assets.length === 0" class="empty-state">
        <div class="empty-visual" aria-hidden="true">+</div>
        <h3>아직 올라온 기록이 없어요</h3>
        <p>첫 사진이나 동영상을 올리면 날짜별 타임라인으로 쌓입니다.</p>
      </div>

      <div v-else class="timeline-list">
        <section v-for="group in timelineGroups" :key="group.key" class="timeline-day">
          <div class="day-heading">
            <h3>{{ group.label }}</h3>
            <span>{{ group.assets.length }}개</span>
          </div>

          <div class="gallery-grid" :class="{ 'is-selection-mode': isSelectionMode, 'is-drag-selecting': isDragSelecting }">
            <button
              v-for="asset in group.assets"
              :key="asset.id"
              class="asset-card"
              :class="{ 'is-selected': isSelected(asset.id) }"
              type="button"
              :data-asset-id="asset.id"
              :aria-label="`${asset.filename} ${isSelectionMode ? '선택하기' : '자세히 보기'}`"
              @click="handleAssetClick($event, asset)"
              @pointerdown="handleAssetPointerDown($event, asset)"
              @pointermove="handleAssetPointerMove"
              @pointerup="handleAssetPointerEnd"
              @pointercancel="handleAssetPointerEnd"
            >
              <div class="asset-thumb" v-lazy-thumbnail="asset.id">
                <img v-if="isThumbnailVisible(asset.id)" :src="mediaThumbnailUrl(asset)" :alt="asset.filename" :loading="thumbnailLoadingMode(asset.id)" :fetchpriority="thumbnailFetchPriority(asset.id)" decoding="async" />
                <div v-else class="asset-thumb-placeholder" aria-hidden="true"></div>
                <span v-if="asset.mediaType === 'VIDEO'" class="video-badge" aria-hidden="true">▶</span>
                <span v-if="isSelectionMode" class="select-badge" :class="{ 'is-on': isSelected(asset.id) }" aria-hidden="true">
                  {{ isSelected(asset.id) ? '✓' : '' }}
                </span>
              </div>
            </button>
          </div>
        </section>
      </div>

      <div v-if="assets.length && hasMoreAssets" ref="pageSentinel" class="load-more-sentinel" aria-live="polite">
        {{ isLoadingAssets ? '불러오는 중' : '더 보기' }}
      </div>
    </section>

    <div v-if="canAccessAlbum" class="floating-actions" :class="{ 'is-open': isActionMenuOpen }">
      <div v-if="isActionMenuOpen" class="floating-menu" role="menu">
        <button v-if="canManageMedia" type="button" role="menuitem" @click="openFilePicker">사진/동영상 선택</button>
        <button type="button" role="menuitem" @click="toggleSelectionMode">
          {{ isSelectionMode ? '선택 취소' : '선택 모드' }}
        </button>
        <button type="button" role="menuitem" @click="refreshAssets">새로고침</button>
        <button v-if="isAdmin" type="button" role="menuitem" @click="openAdminPanel">가족 승인 관리</button>
        <button type="button" role="menuitem" :disabled="isPushLoading || pushState === 'subscribed' || pushState === 'blocked' || pushState === 'server-disabled'" @click="enablePushNotifications">
          {{ pushButtonLabel }}
        </button>
      </div>
      <button
        class="floating-trigger"
        type="button"
        :aria-expanded="isActionMenuOpen"
        aria-label="앨범 조작 메뉴"
        @click="isActionMenuOpen = !isActionMenuOpen"
      >
        <span></span>
        <span></span>
        <span></span>
        <span></span>
      </button>
    </div>

    <div v-if="canAccessAlbum && activeAsset" class="detail-backdrop" @click.self="handleDetailBackdropClick" @wheel.prevent @touchmove.self.prevent>
      <article class="detail-panel" role="dialog" aria-modal="true" aria-labelledby="asset-detail-title" @click="handleDetailPanelClick">
        <button class="detail-close" type="button" aria-label="닫기" @click="closeAsset">×</button>
        <button class="detail-nav detail-prev" type="button" :disabled="!hasPreviousAsset" aria-label="이전 사진" @click="showPreviousAsset">‹</button>
        <button class="detail-nav detail-next" type="button" :disabled="!hasNextAsset" aria-label="다음 사진" @click="showNextAsset">›</button>

        <div class="detail-preview" @touchstart.passive="onDetailTouchStart" @touchend.passive="onDetailTouchEnd">
          <video
            v-if="activeAsset.mediaType === 'VIDEO'"
            :src="mediaViewUrl(activeAsset)"
            :poster="mediaThumbnailUrl(activeAsset)"
            controls
            playsinline
          ></video>
          <img v-else :src="mediaViewUrl(activeAsset)" :alt="activeAsset.filename" />
        </div>
        <div class="detail-quick-actions" @click.stop>
          <div v-if="isDetailMenuOpen" class="detail-menu" role="menu">
            <button type="button" role="menuitem" @click.stop="toggleDetailInfo">상세</button>
            <button v-if="canManageMedia" type="button" role="menuitem" class="danger-button" @click="deleteAsset(activeAsset)">삭제</button>
          </div>
          <button class="detail-save-trigger" type="button" :aria-label="downloadActionLabel" @click="downloadAsset(activeAsset)">&#8595;</button>
          <button class="detail-menu-trigger" type="button" :aria-expanded="isDetailMenuOpen" aria-label="상세 조작 메뉴" @click.stop="isDetailMenuOpen = !isDetailMenuOpen">i</button>
        </div>

        <div v-if="isDetailInfoOpen" class="detail-info">
          <p class="eyebrow">{{ activeAsset.mediaType === 'VIDEO' ? 'Video' : 'Photo' }}</p>
          <h2 id="asset-detail-title">{{ activeAsset.filename }}</h2>
          <dl>
            <div>
              <dt>기록일</dt>
              <dd>{{ formatDateTime(assetDate(activeAsset)) }}</dd>
            </div>
            <div>
              <dt>파일 크기</dt>
              <dd>{{ formatBytes(activeAsset.byteSize) }}</dd>
            </div>
            <div>
              <dt>상태</dt>
              <dd>{{ activeAsset.uploadStatus }}</dd>
            </div>
            <div>
              <dt>공개 범위</dt>
              <dd>{{ visibilityLabel(activeAsset.visibility) }}</dd>
            </div>
            <div>
              <dt>올린 사람</dt>
              <dd>{{ uploaderRoleLabel(activeAsset.uploadedByRole) }}</dd>
            </div>
            <div>
              <dt>타입</dt>
              <dd>{{ activeAsset.contentType }}</dd>
            </div>
          </dl>
        </div>
      </article>
    </div>

    <div v-if="isAdminPanelOpen" class="admin-backdrop" @click.self="isAdminPanelOpen = false">
      <section class="admin-panel" role="dialog" aria-modal="true" aria-labelledby="admin-title">
        <button class="detail-close" type="button" aria-label="닫기" @click="isAdminPanelOpen = false">×</button>
        <div class="admin-heading">
          <span class="hero-kicker">Admin</span>
          <h2 id="admin-title">가족 승인 관리</h2>
          <p>카카오 로그인한 사용자를 승인하고 권한을 부여해 주세요.</p>
        </div>
        <div v-if="isLoadingAdminUsers" class="admin-empty">사용자 목록을 불러오는 중이에요.</div>
        <div v-else-if="adminUsers.length === 0" class="admin-empty">아직 로그인한 사용자가 없어요.</div>
        <div v-else class="admin-user-list">
          <article v-for="user in adminUsers" :key="user.id" class="admin-user-card">
            <img v-if="user.profileImageUrl" :src="user.profileImageUrl" alt="" />
            <div v-else class="admin-avatar-placeholder">{{ user.nickname?.slice(0, 1) }}</div>
            <div>
              <strong>{{ user.nickname }}</strong>
              <span>{{ roleLabel(user.role) }} · {{ user.kakaoId }}</span>
            </div>
            <div class="admin-user-actions">
              <button type="button" :disabled="user.role === 'FAMILY'" @click="updateUserRole(user, 'FAMILY')">가족</button>
              <button type="button" :disabled="user.role === 'MOTHER'" @click="updateUserRole(user, 'MOTHER')">엄마</button>
              <button type="button" :disabled="user.role === 'FATHER'" @click="updateUserRole(user, 'FATHER')">아빠</button>
              <button type="button" :disabled="user.role === 'PENDING'" class="danger-button" @click="updateUserRole(user, 'PENDING')">권한 회수</button>
            </div>
          </article>
        </div>
      </section>
    </div>
    <div v-if="toastMessage" class="toast-message" role="status" aria-live="polite">{{ toastMessage }}</div>
  </main>
</template>
