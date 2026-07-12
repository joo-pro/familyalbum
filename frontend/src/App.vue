<script setup>
import { computed, onMounted, ref } from 'vue'

const apiStatus = ref('확인 중')
const assets = ref([])
const selectedFiles = ref([])
const isUploading = ref(false)
const uploadMessage = ref('')

const totalSize = computed(() => {
  return selectedFiles.value.reduce((sum, file) => sum + file.size, 0)
})

onMounted(async () => {
  await Promise.all([checkApi(), loadAssets()])
})

async function checkApi() {
  try {
    const response = await fetch('/api/health')
    apiStatus.value = response.ok ? '연결됨' : '응답 오류'
  } catch {
    apiStatus.value = '연결 안 됨'
  }
}

async function loadAssets() {
  const response = await fetch('/api/media')
  if (response.ok) {
    assets.value = await response.json()
  }
}

function onFileChange(event) {
  selectedFiles.value = Array.from(event.target.files ?? [])
  uploadMessage.value = ''
}

async function uploadSelectedFiles() {
  if (selectedFiles.value.length === 0) {
    return
  }

  isUploading.value = true
  uploadMessage.value = '업로드 URL 생성 중'

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
        throw new Error('업로드 URL 생성 실패')
      }

      const presign = await presignResponse.json()
      uploadMessage.value = `${file.name} 업로드 중`

      const uploadResponse = await fetch(presign.uploadUrl, {
        method: 'PUT',
        headers: { 'Content-Type': file.type || 'application/octet-stream' },
        body: file,
      })

      if (!uploadResponse.ok) {
        throw new Error('스토리지 업로드 실패')
      }

      await fetch('/api/media/upload-complete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ assetId: presign.assetId }),
      })
    }

    uploadMessage.value = '업로드 완료'
    selectedFiles.value = []
    await loadAssets()
  } catch (error) {
    uploadMessage.value = error.message
  } finally {
    isUploading.value = false
  }
}

function formatBytes(bytes) {
  if (bytes < 1024 * 1024) {
    return `${Math.round(bytes / 1024)} KB`
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <main class="app-shell">
    <section class="topbar">
      <div>
        <p class="eyebrow">FamilyAlbum</p>
        <h1>가족만 보는 아기 앨범</h1>
      </div>
      <div class="status">
        <span></span>
        API {{ apiStatus }}
      </div>
    </section>

    <section class="upload-panel">
      <div>
        <h2>사진과 동영상 올리기</h2>
        <p>원본은 비공개 버킷에 보관하고, 웹에서는 변환본을 재생하는 구조로 갈 예정입니다.</p>
      </div>

      <label class="file-picker">
        <input type="file" multiple accept="image/*,video/*" @change="onFileChange" />
        <span>파일 선택</span>
      </label>

      <div v-if="selectedFiles.length" class="selection">
        <strong>{{ selectedFiles.length }}개 선택</strong>
        <span>{{ formatBytes(totalSize) }}</span>
      </div>

      <button :disabled="!selectedFiles.length || isUploading" @click="uploadSelectedFiles">
        {{ isUploading ? '업로드 중' : '업로드 시작' }}
      </button>

      <p v-if="uploadMessage" class="message">{{ uploadMessage }}</p>
    </section>

    <section class="gallery">
      <div class="section-title">
        <h2>최근 업로드</h2>
        <span>{{ assets.length }}개</span>
      </div>

      <div v-if="assets.length === 0" class="empty-state">
        아직 올라온 사진이나 동영상이 없습니다.
      </div>

      <article v-for="asset in assets" :key="asset.id" class="asset-card">
        <div class="asset-thumb">
          {{ asset.mediaType === 'VIDEO' ? 'Video' : 'Photo' }}
        </div>
        <div>
          <h3>{{ asset.filename }}</h3>
          <p>{{ asset.mediaType }} · {{ formatBytes(asset.byteSize) }} · {{ asset.uploadStatus }}</p>
        </div>
      </article>
    </section>
  </main>
</template>
