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

const uploadedCount = computed(() => assets.value.length)
const photoCount = computed(() => assets.value.filter((asset) => asset.mediaType === 'IMAGE').length)
const videoCount = computed(() => assets.value.filter((asset) => asset.mediaType === 'VIDEO').length)
const totalSize = computed(() => selectedFiles.value.reduce((sum, file) => sum + file.size, 0))
const uploadButtonLabel = computed(() => {
  if (isUploading.value) return '업로드 중'
  if (selectedFiles.value.length > 0) return `${selectedFiles.value.length}개 업로드`
  return '파일을 먼저 선택해 주세요'
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
  uploadMessage.value = '업로드 URL을 준비하고 있어요.'

  try {
    for (const file of selectedFiles.value) {
      const presignResponse = await fetch('/api/media/upload-url', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          filename: file.name,
          contentType: file.type || 'application/octet-stream',
          byteSize: file.size,
        }),
      })

      if (!presignResponse.ok) {
        throw new Error('업로드 URL을 만들지 못했어요.')
      }

      const presign = await presignResponse.json()
      uploadMessage.value = `${file.name} 업로드 중이에요.`

      const uploadResponse = await fetch(presign.uploadUrl, {
        method: 'PUT',
        headers: { 'Content-Type': file.type || 'application/octet-stream' },
        body: file,
      })

      if (!uploadResponse.ok) {
        throw new Error('스토리지 업로드에 실패했어요.')
      }

      await fetch('/api/media/upload-complete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ assetId: presign.assetId }),
      })
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

function formatBytes(bytes) {
  if (!bytes) return '0 KB'
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`
}

function formatDate(value) {
  if (!value) return '날짜 미정'
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
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
          <h2>최근 기록</h2>
        </div>
        <button class="ghost-button" type="button" @click="loadAssets">
          <span aria-hidden="true">↻</span>
          새로고침
        </button>
      </div>

      <div v-if="assets.length === 0" class="empty-state">
        <div class="empty-visual" aria-hidden="true">♡</div>
        <h3>아직 올라온 기록이 없어요</h3>
        <p>첫 사진이나 동영상을 올리면 이곳에 시간순으로 쌓입니다.</p>
      </div>

      <div v-else class="gallery-grid">
        <article v-for="asset in assets" :key="asset.id" class="asset-card">
          <div class="asset-thumb">
            <span>{{ asset.mediaType === 'VIDEO' ? '▶' : '□' }}</span>
            <small>{{ asset.mediaType === 'VIDEO' ? 'Video' : 'Photo' }}</small>
          </div>
          <div class="asset-body">
            <p>{{ formatDate(asset.createdAt) }}</p>
            <h3>{{ asset.filename }}</h3>
            <span>{{ formatBytes(asset.byteSize) }} · {{ asset.uploadStatus }}</span>
          </div>
        </article>
      </div>
    </section>
  </main>
</template>