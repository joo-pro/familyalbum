<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

const defaultConfig = {
  appTitle: '지웅이 성장일기',
}

const appConfig = ref(defaultConfig)
const assets = ref([])
const selectedFiles = ref([])
const isUploading = ref(false)
const uploadMessage = ref('')
const activeAsset = ref(null)
const isSelectionMode = ref(false)
const selectedAssetIds = ref(new Set())
const isActionMenuOpen = ref(false)
const isDetailMenuOpen = ref(false)
const isDetailInfoOpen = ref(false)
const touchStartX = ref(null)
const fileInput = ref(null)
const pageSentinel = ref(null)
const nextCursor = ref(null)
const isLoadingAssets = ref(false)
const hasMoreAssets = ref(true)
const visibleThumbnailIds = ref(new Set())

let loadMoreObserver
let thumbnailObserver


const totalSize = computed(() => selectedFiles.value.reduce((sum, file) => sum + file.size, 0))
const selectedCount = computed(() => selectedAssetIds.value.size)
const selectedAssetIdList = computed(() => Array.from(selectedAssetIds.value))
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
  await Promise.all([loadAppConfig(), loadAssets()])
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

async function loadAssets() {
  assets.value = []
  nextCursor.value = null
  hasMoreAssets.value = true
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
    if (response.ok) {
      const page = await response.json()
      const items = Array.isArray(page) ? page : (page.items ?? [])
      assets.value = nextCursor.value ? [...assets.value, ...items] : items
      nextCursor.value = Array.isArray(page) ? null : (page.nextCursor ?? null)
      hasMoreAssets.value = Array.isArray(page) ? false : Boolean(page.hasMore)
      await nextTick()
      setupLoadMoreObserver()
    }
  } catch {
    if (!nextCursor.value) assets.value = []
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
  return visibleThumbnailIds.value.has(assetId)
}
function openFilePicker() {
  fileInput.value?.click()
  isActionMenuOpen.value = false
}

function onFileChange(event) {
  selectedFiles.value = Array.from(event.target.files ?? [])
  uploadMessage.value = ''
}

async function uploadSelectedFiles() {
  if (selectedFiles.value.length === 0) return

  isActionMenuOpen.value = false
  isUploading.value = true
  uploadMessage.value = '업로드를 준비하고 있어요.'

  try {
    let uploadedCount = 0
    let duplicateCount = 0

    for (const file of selectedFiles.value) {
      uploadMessage.value = `${file.name} 업로드 중이에요.`

      const formData = new FormData()
      formData.append('file', file)
      formData.append('capturedAt', new Date(file.lastModified).toISOString())

      const response = await fetch('/api/media/upload', {
        method: 'POST',
        body: formData,
      })

      if (!response.ok) {
        const message = await response.text()
        throw new Error(message || '업로드에 실패했어요.')
      }

      const result = await response.json()
      if (result.duplicate) {
        duplicateCount += 1
      } else {
        uploadedCount += 1
      }
    }

    uploadMessage.value = duplicateCount > 0
      ? `업로드 완료: 새 파일 ${uploadedCount}개, 중복 ${duplicateCount}개 건너뜀`
      : '업로드가 완료됐어요.'
    selectedFiles.value = []
    if (fileInput.value) fileInput.value.value = ''
    await loadAssets()
  } catch (error) {
    uploadMessage.value = error.message
  } finally {
    isUploading.value = false
  }
}

function handleAssetClick(asset) {
  if (isSelectionMode.value) {
    toggleAssetSelection(asset.id)
    return
  }
  openAsset(asset)
}

function openAsset(asset) {
  activeAsset.value = asset
  isDetailMenuOpen.value = false
  isDetailInfoOpen.value = false
}

function closeAsset() {
  activeAsset.value = null
  isDetailMenuOpen.value = false
  isDetailInfoOpen.value = false
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
  if (isMobileDevice.value) {
    await shareAssets([asset])
    return
  }

  const response = await fetch(`/api/media/${asset.id}/download-url`, { method: 'POST' })
  if (!response.ok) throw new Error('다운로드 링크를 만들지 못했어요.')
  const payload = await response.json()
  window.location.href = payload.downloadUrl
}

async function downloadSelectedAssets() {
  if (selectedCount.value === 0) return
  isActionMenuOpen.value = false
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
    return
  }

  if (files.length === 1) {
    const url = URL.createObjectURL(files[0])
    window.open(url, '_blank', 'noopener')
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
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
  return new Date(value).toISOString().slice(0, 10)
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
      <div class="hero-grid">
        <div class="hero-copy">
          <h1>{{ appConfig.appTitle }}</h1>
        </div>
      </div>
    </section>

    <input ref="fileInput" class="hidden-file-input" type="file" multiple accept="image/*,video/*,.heic,.heif,.heics,.heifs,.mov,.m4v,image/heic,image/heif,video/quicktime" @change="onFileChange" />

    <section v-if="selectedFiles.length || uploadMessage" class="upload-summary" aria-live="polite">
      <div>
        <strong v-if="selectedFiles.length">{{ selectedFiles.length }}개 선택됨</strong>
        <strong v-else>업로드 상태</strong>
        <span>{{ selectedFiles.length ? formatBytes(totalSize) : uploadMessage }}</span>
      </div>
      <p v-if="uploadMessage && selectedFiles.length">{{ uploadMessage }}</p>
    </section>

    <section class="content-section">
      <div v-if="isSelectionMode" class="selection-chip">
        <strong>{{ selectedCount }}개 선택됨</strong>
        <button type="button" @click="toggleSelectionMode">취소</button>
      </div>

      <div v-if="assets.length === 0" class="empty-state">
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

          <div class="gallery-grid">
            <button
              v-for="asset in group.assets"
              :key="asset.id"
              class="asset-card"
              :class="{ 'is-selected': isSelected(asset.id) }"
              type="button"
              :aria-label="`${asset.filename} ${isSelectionMode ? '선택하기' : '자세히 보기'}`"
              @click="handleAssetClick(asset)"
            >
              <div class="asset-thumb" v-lazy-thumbnail="asset.id">
                <img v-if="isThumbnailVisible(asset.id)" :src="mediaThumbnailUrl(asset)" :alt="asset.filename" loading="lazy" decoding="async" />
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

    <div class="floating-actions" :class="{ 'is-open': isActionMenuOpen }">
      <div v-if="isActionMenuOpen" class="floating-menu" role="menu">
        <button type="button" role="menuitem" @click="openFilePicker">사진/동영상 선택</button>
        <button type="button" role="menuitem" :disabled="!selectedFiles.length || isUploading" @click="uploadSelectedFiles">
          {{ uploadButtonLabel }}
        </button>
        <button type="button" role="menuitem" @click="toggleSelectionMode">
          {{ isSelectionMode ? '선택 취소' : '선택 모드' }}
        </button>
        <button type="button" role="menuitem" @click="refreshAssets">새로고침</button>
        <button v-if="isSelectionMode" type="button" role="menuitem" :disabled="selectedCount === 0" @click="downloadSelectedAssets">
          선택 {{ downloadActionLabel }}
        </button>
        <button v-if="isSelectionMode" type="button" role="menuitem" :disabled="selectedCount === 0" class="danger-button" @click="deleteSelectedAssets">
          선택 삭제
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

    <div v-if="activeAsset" class="detail-backdrop" @click.self="closeAsset">
      <article class="detail-panel" role="dialog" aria-modal="true" aria-labelledby="asset-detail-title">
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
        <div class="detail-quick-actions">
          <div v-if="isDetailMenuOpen" class="detail-menu" role="menu">
            <button type="button" role="menuitem" @click="downloadAsset(activeAsset)">{{ downloadActionLabel }}</button>
            <button type="button" role="menuitem" @click="toggleDetailInfo">상세</button>
            <button type="button" role="menuitem" class="danger-button" @click="deleteAsset(activeAsset)">삭제</button>
          </div>
          <button class="detail-menu-trigger" type="button" :aria-expanded="isDetailMenuOpen" aria-label="상세 조작 메뉴" @click="isDetailMenuOpen = !isDetailMenuOpen">i</button>
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
              <dt>타입</dt>
              <dd>{{ activeAsset.contentType }}</dd>
            </div>
          </dl>
        </div>
      </article>
    </div>
  </main>
</template>
