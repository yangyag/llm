<script setup lang="ts">
import { ref } from "vue";
import { useAuthStore } from "~/stores/auth";
import type { ApiError } from "~/types/api";

const auth = useAuthStore();
const username = ref("");
const password = ref("");
const error = ref("");
const submitting = ref(false);
const usernameHint = ref("");
const isComposing = ref(false);

const usernamePatternMessage = "아이디는 영문과 숫자만 입력할 수 있습니다.";

function sanitizeUsername(value: string): string {
  return value.replace(/[^A-Za-z0-9]/g, "");
}

function isValidUsername(value: string): boolean {
  return /^[A-Za-z0-9]+$/.test(value);
}

function showUsernameHint() {
  usernameHint.value = usernamePatternMessage;
}

function updateUsername(nextValue: string) {
  const sanitizedValue = sanitizeUsername(nextValue);
  username.value = sanitizedValue;
  usernameHint.value = nextValue === sanitizedValue ? "" : usernamePatternMessage;
  if (error.value) {
    error.value = "";
  }
}

async function handleSubmit() {
  error.value = "";
  const currentUsername = sanitizeUsername(username.value);

  if (!isValidUsername(currentUsername)) {
    username.value = currentUsername;
    showUsernameHint();
    error.value = usernamePatternMessage;
    return;
  }

  submitting.value = true;
  username.value = currentUsername;

  try {
    await auth.login(currentUsername, password.value);
    await navigateTo("/");
  } catch (err) {
    const e = err as ApiError;
    if (e.status === 401) {
      error.value = "아이디 또는 비밀번호가 올바르지 않습니다.";
    } else if (e.status === 400 && e.code === "INVALID_REQUEST") {
      error.value = usernamePatternMessage;
    } else {
      error.value = e.message;
    }
  } finally {
    submitting.value = false;
  }
}

function handleUsernamePaste(event: ClipboardEvent) {
  const pastedText = event.clipboardData?.getData("text") ?? "";
  const sanitizedText = sanitizeUsername(pastedText);

  if (pastedText === sanitizedText) {
    return;
  }

  event.preventDefault();

  const input = event.currentTarget as HTMLInputElement;
  const currentValue = input.value;
  const selectionStart = input.selectionStart ?? currentValue.length;
  const selectionEnd = input.selectionEnd ?? currentValue.length;
  const nextValue = `${currentValue.slice(0, selectionStart)}${sanitizedText}${currentValue.slice(selectionEnd)}`;

  username.value = nextValue;
  showUsernameHint();

  if (error.value) {
    error.value = "";
  }
}

function handleUsernameInput(event: Event) {
  const nextValue = (event.target as HTMLInputElement).value;
  if (isComposing.value) {
    username.value = nextValue;
    return;
  }
  updateUsername(nextValue);
}

function handleUsernameCompositionEnd(event: CompositionEvent) {
  isComposing.value = false;
  updateUsername((event.target as HTMLInputElement).value);
}

function handleUsernameBlur(event: Event) {
  updateUsername((event.target as HTMLInputElement).value);
}
</script>

<template>
  <main class="board-page">
    <section class="board-shell login-shell">
      <header class="board-header">
        <div>
          <p class="eyebrow">Admin Login</p>
          <h1>관리자 로그인</h1>
        </div>
      </header>

      <section class="card login-card">
        <form class="form-grid" @submit.prevent="handleSubmit">
          <label class="field">
            <span>아이디</span>
            <input
              :value="username"
              @input="handleUsernameInput"
              @compositionstart="isComposing = true"
              @compositionend="handleUsernameCompositionEnd"
              @blur="handleUsernameBlur"
              @paste="handleUsernamePaste"
              autofocus
              autocomplete="username"
              inputmode="text"
              pattern="[A-Za-z0-9]+"
              :title="usernamePatternMessage"
              required
            />
          </label>
          <p v-if="usernameHint" class="field-hint">{{ usernameHint }}</p>
          <label class="field">
            <span>비밀번호</span>
            <input
              v-model="password"
              type="password"
              autocomplete="current-password"
              required
            />
          </label>
          <p v-if="error" class="message-banner error">{{ error }}</p>
          <button type="submit" class="primary-button wide-button" :disabled="submitting">
            {{ submitting ? "로그인 중..." : "로그인" }}
          </button>
        </form>
      </section>
    </section>
  </main>
</template>
