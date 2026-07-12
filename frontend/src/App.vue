<script setup>
import { computed, onMounted, ref } from 'vue'

const defaultConfig = {
  appTitle: '지웅이 성장일기',
  appSubtitle: '우리 가족이 함께 기록하는 사진과 동영상 앨범',
  babyName: '지웅이',
}

const appConfig = ref(defaultConfig)
const apiStatus = ref('확인 중')
const assets = ref([])
const selectedFiles = ref([])
const isUploading = ref(false)
const uploadMessage = ref('')
const activeAsset = ref(null)
const isSelectionMode = ref(false)
const selectedAssetIds = ref(new Set())

const uploadedCount = computed(() => assets.value.length)
const photoCount = computed(() => assets.value.filter((asset) => asset.mediaType === 'IMAGE').length)
const videoCount = computed(() => assets.value.filter((asset) => asset.mediaType === 'VIDEO').length)
const totalSize = computed(() => selectedFiles.value.reduce((sum, file) => sum + file.size, 0))
const selectedCount = computed(() => selectedAssetIds.value.size)
const selectedAssetIdList = computed(() => Array.from(selectedAssetIds.value))
const uploadButtonLabel = computed(() => {
  if (isUploading.value) return '업로드 중'
  if (selectedFiles.value.length > 0) return `${selectedFiles.value.length}개 업로드`
  return '파일을 먼저 선택해 주세요'
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
  await Promise.all([loadAppConfig(), checkApi(), loadAssets()])
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

async function checkApi() {
  try {
    const response = await fetch('/api/health')
    apiStatus.value = response.ok ? '연결됨' : '응답 오류'
  } catch {
    apiStatus.value = '연결 안 됨'
  }
}

async function loadAssets() {
  try {
    const response = await fetch('/api/media')
    if (response.ok) {
      assets.value = await response.json()
    }
  } catch {
    assets.value = []
  }
}

function onFileChange(event) {
  selectedFiles.value = Array.from(event.target.files ?? [])
  uploadMessage.value = ''
}

async function uploadSelectedFiles() {
  if (selectedFiles.value.length === 0) return

  isUploading.value = true
  uploadMessage.value = '서버를 통해 업로드를 준비하고 있어요.'

  try {
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
    }

    uploadMessage.value = '업로드가 완료됐어요.'
    selectedFiles.value = []
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
}

function closeAsset() {
  activeAsset.value = null
}

function toggleSelectionMode() {
  isSelectionMode.value = !isSelectionMode.value
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

async function downloadAsset(asset) {
  const response = await fetch(`/api/media/${asset.id}/download-url`, { method: 'POST' })
  if (!response.ok) throw new Error('다운로드 링크를 만들지 못했어요.')
  const payload = await response.json()
  window.location.href = payload.downloadUrl
}

async function downloadSelectedAssets() {
  if (selectedCount.value === 0) return
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

async function deleteAsset(asset) {
  if (!confirm(`${asset.filename} 파일을 삭제할까요?`)) return
  const response = await fetch(`/api/media/${asset.id}`, { method: 'DELETE' })
  if (!response.ok) throw new Error('삭제하지 못했어요.')
  closeAsset()
  await loadAssets()
}

async function deleteSelectedAssets() {
  if (selectedCount.value === 0) return
  if (!confirm(`선택한 ${selectedCount.value}개 파일을 삭제할까요?`)) return
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
      <nav class="top-nav" aria-label="서비스 상태">
        <span class="brand-mark">FA</span>
        <div class="status-pill" :class="{ 'is-offline': apiStatus !== '연결됨' }">
          <span aria-hidden="true"></span>
          API {{ apiStatus }}
        </div>
      </nav>

      <div class="hero-grid">
        <div class="hero-copy">
          <p class="eyebrow">FamilyAlbum</p>
          <h1>{{ appConfig.appTitle }}</h1>
          <p class="hero-subtitle">{{ appConfig.appSubtitle }}</p>
          <div class="hero-actions">
            <label class="primary-action">
              <input type="file" multiple accept="image/*,video/*" @change="onFileChange" />
              <span aria-hidden="true">+</span>
              사진과 동영상 선택
            </label>
            <button class="secondary-action" type="button" :disabled="!selectedFiles.length || isUploading" @click="uploadSelectedFiles">
              <span aria-hidden="true">↑</span>
              {{ uploadButtonLabel }}
            </button>
          </div>
        </div>

        <aside class="today-panel" aria-label="앨범 요약">
          <div class="date-card">
            <span>오늘의 기록</span>
            <strong>{{ appConfig.babyName }}</strong>
          </div>
          <div class="metric-grid">
            <div>
              <strong>{{ uploadedCount }}</strong>
              <span>전체</span>
            </div>
            <div>
              <strong>{{ photoCount }}</strong>
              <span>사진</span>
            </div>
            <div>
              <strong>{{ videoCount }}</strong>
              <span>동영상</span>
            </div>
          </div>
        </aside>
      </div>
    </section>

    <section v-if="selectedFiles.length || uploadMessage" class="upload-summary" aria-live="polite">
      <div>
        <strong v-if="selectedFiles.length">{{ selectedFiles.length }}개 선택됨</strong>
        <strong v-else>업로드 상태</strong>
        <span>{{ selectedFiles.length ? formatBytes(totalSize) : uploadMessage }}</span>
      </div>
      <p v-if="uploadMessage && selectedFiles.length">{{ uploadMessage }}</p>
    </section>

    <section class="content-section">
      <div class="section-heading">
        <div>
          <p class="eyebrow">Timeline</p>
          <h2>날짜별 성장 기록</h2>
        </div>
        <div class="section-actions">
          <button class="ghost-button" type="button" @click="toggleSelectionMode">
            <span aria-hidden="true">✓</span>
            {{ isSelectionMode ? '선택 취소' : '선택' }}
          </button>
          <button class="ghost-button" type="button" @click="loadAssets">
            <span aria-hidden="true">↻</span>
            새로고침
          </button>
        </div>
      </div>

      <div v-if="isSelectionMode" class="selection-toolbar">
        <strong>{{ selectedCount }}개 선택됨</strong>
        <div>
          <button type="button" :disabled="selectedCount === 0" @click="downloadSelectedAssets">다운로드</button>
          <button type="button" :disabled="selectedCount === 0" class="danger-button" @click="deleteSelectedAssets">삭제</button>
        </div>
      </div>

      <div v-if="assets.length === 0" class="empty-state">
        <div class="empty-visual" aria-hidden="true">♡</div>
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
              <div class="asset-thumb">
                <video
                  v-if="asset.mediaType === 'VIDEO'"
                  :src="mediaViewUrl(asset)"
                  preload="metadata"
                  muted
                  playsinline
                ></video>
                <img v-else :src="mediaViewUrl(asset)" :alt="asset.filename" loading="lazy" />
                <span v-if="asset.mediaType === 'VIDEO'" class="video-badge" aria-hidden="true">▶</span>
                <span v-if="isSelectionMode" class="select-badge" :class="{ 'is-on': isSelected(asset.id) }" aria-hidden="true">
                  {{ isSelected(asset.id) ? '✓' : '' }}
                </span>
              </div>
            </button>
          </div>
        </section>
      </div>
    </section>

    <div v-if="activeAsset" class="detail-backdrop" @click.self="closeAsset">
      <article class="detail-panel" role="dialog" aria-modal="true" aria-labelledby="asset-detail-title">
        <button class="detail-close" type="button" aria-label="닫기" @click="closeAsset">×</button>
        <div class="detail-preview">
          <video
            v-if="activeAsset.mediaType === 'VIDEO'"
            :src="mediaViewUrl(activeAsset)"
            controls
            playsinline
          ></video>
          <img v-else :src="mediaViewUrl(activeAsset)" :alt="activeAsset.filename" />
        </div>
        <div class="detail-info">
          <p class="eyebrow">{{ activeAsset.mediaType === 'VIDEO' ? 'Video' : 'Photo' }}</p>
          <h2 id="asset-detail-title">{{ activeAsset.filename }}</h2>
          <div class="detail-actions">
            <button type="button" @click="downloadAsset(activeAsset)">다운로드</button>
            <button type="button" class="danger-button" @click="deleteAsset(activeAsset)">삭제</button>
          </div>
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