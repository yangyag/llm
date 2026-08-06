<script setup lang="ts">
import { ref } from "vue";

const props = defineProps<{
  inputKey: number;
  label: string;
}>();

const emit = defineEmits<{
  "files-selected": [files: File[]];
}>();

const fileInput = ref<HTMLInputElement | null>(null);
const isDragging = ref(false);
let dragEnterDepth = 0;

function hasFiles(event: DragEvent): boolean {
  return Array.from(event.dataTransfer?.types ?? []).includes("Files");
}

function selectFiles(files: FileList | null) {
  const selectedFiles = Array.from(files ?? []);
  if (selectedFiles.length > 0) {
    emit("files-selected", selectedFiles);
  }
}

function openFilePicker() {
  fileInput.value?.click();
}

function onInputChange(event: Event) {
  const input = event.target as HTMLInputElement;
  selectFiles(input.files);
  // 같은 파일을 다시 선택해도 change 이벤트가 발생하도록 picker 값을 비운다.
  input.value = "";
}

function onDragEnter(event: DragEvent) {
  if (!hasFiles(event)) {
    return;
  }
  event.preventDefault();
  dragEnterDepth += 1;
  isDragging.value = true;
}

function onDragOver(event: DragEvent) {
  if (!hasFiles(event)) {
    return;
  }
  event.preventDefault();
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = "copy";
  }
}

function onDragLeave() {
  dragEnterDepth = Math.max(dragEnterDepth - 1, 0);
  if (dragEnterDepth === 0) {
    isDragging.value = false;
  }
}

function onDrop(event: DragEvent) {
  event.preventDefault();
  dragEnterDepth = 0;
  isDragging.value = false;
  selectFiles(event.dataTransfer?.files ?? null);
}
</script>

<template>
  <div class="field">
    <span>{{ props.label }}</span>
    <div
      class="attachment-dropzone"
      :class="{ 'is-dragging': isDragging }"
      role="group"
      :aria-label="`${props.label}: 파일을 끌어 놓거나 파일 선택 버튼을 누르세요.`"
      @dragenter="onDragEnter"
      @dragover="onDragOver"
      @dragleave="onDragLeave"
      @drop="onDrop"
    >
      <input
        :key="props.inputKey"
        ref="fileInput"
        class="attachment-file-input"
        type="file"
        multiple
        tabindex="-1"
        :aria-label="`${props.label} 선택`"
        @change="onInputChange"
      />
      <strong class="attachment-dropzone-drag-title">{{ isDragging ? "여기에 파일을 놓으세요" : "파일을 끌어 놓으세요" }}</strong>
      <p class="attachment-dropzone-help">또는 파일 선택 버튼을 눌러주세요.</p>
      <button type="button" class="ghost-button" @click="openFilePicker">파일 선택</button>
      <p class="attachment-dropzone-mobile-help">모바일에서는 파일 선택 버튼을 눌러주세요.</p>
      <p class="sr-only" aria-live="polite">
        {{ isDragging ? "파일을 놓으면 첨부 목록에 추가됩니다." : "" }}
      </p>
    </div>
  </div>
</template>
