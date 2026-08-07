<script setup lang="ts">
// 사용자 관리 페이지 — ADMIN 전용. 사용자 추가/수정(역할·비밀번호)/삭제.
import { computed, onMounted, ref } from "vue";
import { useAuthStore } from "~/stores/auth";
import { useIdleTimeout } from "~/composables/useIdleTimeout";
import { createUser, deleteUser, listUsers, updateUser } from "~/services/api";
import type { ApiError, UserAccount, UserRole } from "~/types/api";

const auth = useAuthStore();

useIdleTimeout();

const users = ref<UserAccount[]>([]);
const loading = ref(true);
const message = ref("");
const error = ref("");

const searchInput = ref("");
const activeQuery = ref("");

// 추가 폼 상태
const showCreateForm = ref(false);
const createUsername = ref("");
const createPassword = ref("");
const createRole = ref<UserRole>("USER");
const creating = ref(false);
const createError = ref("");
const createUsernameHint = ref("");
const isComposing = ref(false);

// 수정 폼 상태
const editingId = ref<number | null>(null);
const editPassword = ref("");
const editRole = ref<UserRole>("USER");
const saving = ref(false);
const deletingId = ref<number | null>(null);

const usernamePatternMessage = "아이디는 영문과 숫자만 입력할 수 있습니다.";

const adminCount = computed(() => users.value.filter((user) => user.role === "ADMIN").length);
const userCount = computed(() => users.value.filter((user) => user.role === "USER").length);
const listMeta = computed(() => {
  if (loading.value) {
    return "목록을 불러오는 중";
  }
  if (activeQuery.value) {
    return `검색 결과 ${users.value.length}명 · 관리자 ${adminCount.value} · 일반 ${userCount.value}`;
  }
  return `총 ${users.value.length}명 · 관리자 ${adminCount.value} · 일반 ${userCount.value}`;
});

function requireToken(): string {
  if (!auth.token) {
    throw { code: null, status: 401, message: "Authentication required" } as ApiError;
  }
  return auth.token;
}

function sanitizeUsername(value: string): string {
  return value.replace(/[^A-Za-z0-9]/g, "");
}

function isValidUsername(value: string): boolean {
  return /^[A-Za-z0-9]+$/.test(value);
}

function describeError(err: unknown, fallback: string): string {
  const e = err as ApiError;
  switch (e.code) {
    case "DUPLICATE_USERNAME":
      return "이미 존재하는 아이디입니다.";
    case "LAST_ADMIN_PROTECTED":
      return "마지막 남은 관리자는 삭제하거나 일반사용자로 변경할 수 없습니다.";
    case "SELF_DELETE_NOT_ALLOWED":
      return "자기 자신의 계정은 삭제할 수 없습니다.";
    case "FORBIDDEN":
      return "관리자만 사용할 수 있는 기능입니다.";
    case "NOT_FOUND":
      return "존재하지 않는 사용자입니다.";
    default:
      return e.message || fallback;
  }
}

function clearBanners() {
  message.value = "";
  error.value = "";
}

async function loadUsers(query = "") {
  loading.value = true;
  error.value = "";
  activeQuery.value = query.trim();
  try {
    users.value = await listUsers(requireToken(), query);
  } catch (err) {
    users.value = [];
    error.value = describeError(err, "사용자 목록을 불러오지 못했습니다.");
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  void loadUsers();
});

function handleLogout() {
  auth.logout();
  navigateTo("/login", { replace: true });
}

function goToBoard() {
  void navigateTo("/");
}

function handleSearch() {
  clearBanners();
  void loadUsers(searchInput.value);
}

function handleSearchReset() {
  searchInput.value = "";
  clearBanners();
  void loadUsers();
}

function openCreateForm() {
  createUsername.value = "";
  createPassword.value = "";
  createRole.value = "USER";
  createError.value = "";
  createUsernameHint.value = "";
  showCreateForm.value = true;
  clearBanners();
}

function closeCreateForm() {
  showCreateForm.value = false;
  createError.value = "";
  createUsernameHint.value = "";
}

function updateCreateUsername(nextValue: string) {
  const sanitizedValue = sanitizeUsername(nextValue);
  createUsername.value = sanitizedValue;
  createUsernameHint.value = nextValue === sanitizedValue ? "" : usernamePatternMessage;
  if (createError.value) {
    createError.value = "";
  }
}

function handleCreateUsernameInput(event: Event) {
  const nextValue = (event.target as HTMLInputElement).value;
  if (isComposing.value) {
    createUsername.value = nextValue;
    return;
  }
  updateCreateUsername(nextValue);
}

function handleCreateUsernameCompositionEnd(event: CompositionEvent) {
  isComposing.value = false;
  updateCreateUsername((event.target as HTMLInputElement).value);
}

function handleCreateUsernameBlur(event: Event) {
  updateCreateUsername((event.target as HTMLInputElement).value);
}

function handleCreateUsernamePaste(event: ClipboardEvent) {
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
  createUsername.value = nextValue;
  createUsernameHint.value = usernamePatternMessage;
  if (createError.value) {
    createError.value = "";
  }
}

async function submitCreate() {
  createError.value = "";
  const username = sanitizeUsername(createUsername.value.trim());
  createUsername.value = username;

  if (!isValidUsername(username)) {
    createUsernameHint.value = usernamePatternMessage;
    createError.value = usernamePatternMessage;
    return;
  }
  if (createPassword.value.length < 4) {
    createError.value = "비밀번호는 4자 이상이어야 합니다.";
    return;
  }

  creating.value = true;
  try {
    await createUser(
      { username, password: createPassword.value, role: createRole.value },
      requireToken()
    );
    message.value = `${username} 사용자가 추가되었습니다.`;
    error.value = "";
    closeCreateForm();
    await loadUsers(searchInput.value);
  } catch (err) {
    createError.value = describeError(err, "사용자 추가에 실패했습니다.");
  } finally {
    creating.value = false;
  }
}

function startEdit(user: UserAccount) {
  editingId.value = user.id;
  editPassword.value = "";
  editRole.value = user.role;
  error.value = "";
  message.value = "";
  showCreateForm.value = false;
}

function cancelEdit() {
  editingId.value = null;
  editPassword.value = "";
}

async function submitEdit(user: UserAccount) {
  error.value = "";
  if (editPassword.value && editPassword.value.length < 4) {
    error.value = "비밀번호는 4자 이상이어야 합니다.";
    return;
  }

  saving.value = true;
  try {
    const input = editPassword.value
      ? { password: editPassword.value, role: editRole.value }
      : { role: editRole.value };
    await updateUser(user.id, input, requireToken());
    message.value = `${user.username} 사용자 정보가 수정되었습니다.`;
    editingId.value = null;
    editPassword.value = "";
    await loadUsers(searchInput.value);
  } catch (err) {
    error.value = describeError(err, "사용자 수정에 실패했습니다.");
  } finally {
    saving.value = false;
  }
}

async function handleDelete(user: UserAccount) {
  if (user.username === auth.username) {
    error.value = "자기 자신의 계정은 삭제할 수 없습니다.";
    return;
  }

  if (!window.confirm(`${user.username} 사용자를 삭제할까요? 되돌릴 수 없습니다.`)) {
    return;
  }

  error.value = "";
  message.value = "";
  deletingId.value = user.id;
  try {
    await deleteUser(user.id, requireToken());
    message.value = `${user.username} 사용자가 삭제되었습니다.`;
    if (editingId.value === user.id) {
      cancelEdit();
    }
    await loadUsers(searchInput.value);
  } catch (err) {
    error.value = describeError(err, "사용자 삭제에 실패했습니다.");
  } finally {
    deletingId.value = null;
  }
}

function roleLabel(role: UserRole): string {
  return role === "ADMIN" ? "관리자" : "일반사용자";
}

function avatarLetter(username: string): string {
  return (username.trim().charAt(0) || "?").toUpperCase();
}

function formatCreatedAt(value: string): string {
  try {
    return new Intl.DateTimeFormat("ko-KR", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    }).format(new Date(value));
  } catch {
    return value;
  }
}

function isSelf(user: UserAccount): boolean {
  return user.username === auth.username;
}
</script>

<template>
  <main v-if="auth.checked" class="board-page">
    <section class="board-shell">
      <header class="board-header">
        <div>
          <p class="eyebrow">User Management</p>
          <h1>사용자 관리</h1>
          <p class="section-meta">관리자만 계정을 추가·수정·삭제할 수 있습니다.</p>
        </div>
        <div class="board-actions">
          <span v-if="auth.username" class="auth-username">{{ auth.username }}</span>
          <button type="button" class="ghost-button" @click="goToBoard">게시판</button>
          <button type="button" class="ghost-button" @click="handleLogout">로그아웃</button>
          <button
            v-if="!showCreateForm"
            type="button"
            class="primary-button"
            @click="openCreateForm"
          >
            사용자 추가
          </button>
        </div>
      </header>

      <p v-if="message" class="message-banner success" role="status">{{ message }}</p>
      <p v-if="error" class="message-banner error" role="alert">{{ error }}</p>

      <section v-if="showCreateForm" class="card user-create-card">
        <div class="section-heading">
          <div>
            <h2>사용자 추가</h2>
            <p class="section-meta">아이디는 영문/숫자만, 비밀번호는 4자 이상입니다.</p>
          </div>
          <button type="button" class="ghost-button" @click="closeCreateForm">닫기</button>
        </div>

        <form class="form-grid user-create-form" @submit.prevent="submitCreate">
          <div class="user-form-fields">
            <label class="field">
              <span>아이디</span>
              <input
                :value="createUsername"
                autocomplete="off"
                inputmode="text"
                pattern="[A-Za-z0-9]+"
                :title="usernamePatternMessage"
                required
                @input="handleCreateUsernameInput"
                @compositionstart="isComposing = true"
                @compositionend="handleCreateUsernameCompositionEnd"
                @blur="handleCreateUsernameBlur"
                @paste="handleCreateUsernamePaste"
              />
            </label>
            <label class="field">
              <span>비밀번호</span>
              <input
                v-model="createPassword"
                type="password"
                autocomplete="new-password"
                minlength="4"
                required
              />
            </label>
          </div>

          <p v-if="createUsernameHint" class="field-hint">{{ createUsernameHint }}</p>

          <div class="field">
            <span>역할</span>
            <div class="role-options" role="radiogroup" aria-label="역할 선택">
              <label class="role-option" :class="{ active: createRole === 'USER' }">
                <input v-model="createRole" type="radio" value="USER" />
                <span>
                  <strong>일반사용자</strong>
                  <small>게시판 읽기/쓰기</small>
                </span>
              </label>
              <label class="role-option" :class="{ active: createRole === 'ADMIN' }">
                <input v-model="createRole" type="radio" value="ADMIN" />
                <span>
                  <strong>관리자</strong>
                  <small>사용자 관리 포함</small>
                </span>
              </label>
            </div>
          </div>

          <p v-if="createError" class="message-banner error">{{ createError }}</p>

          <div class="inline-actions user-form-actions">
            <button type="submit" class="submit-button" :disabled="creating">
              {{ creating ? "추가 중..." : "추가하기" }}
            </button>
            <button type="button" class="ghost-button" @click="closeCreateForm">취소</button>
          </div>
        </form>
      </section>

      <section class="card">
        <div class="section-heading">
          <div>
            <h2>사용자 목록</h2>
            <p class="section-meta">{{ listMeta }}</p>
          </div>
          <button type="button" class="ghost-button" :disabled="loading" @click="loadUsers(searchInput)">
            새로고침
          </button>
        </div>

        <form class="search-bar user-search-bar" @submit.prevent="handleSearch">
          <label class="field search-field">
            <span>사용자 검색</span>
            <input v-model="searchInput" placeholder="아이디 검색" />
          </label>
          <div class="search-actions">
            <button type="submit" class="submit-button" :disabled="loading">검색</button>
            <button
              type="button"
              class="ghost-button"
              :disabled="loading || (!activeQuery && !searchInput)"
              @click="handleSearchReset"
            >
              초기화
            </button>
          </div>
        </form>

        <p v-if="loading" class="empty-state">불러오는 중...</p>
        <p v-else-if="users.length === 0" class="empty-state">
          {{ activeQuery ? "검색 결과가 없습니다. 다른 아이디로 다시 시도해 보세요." : "등록된 사용자가 없습니다." }}
        </p>

        <ul v-else class="user-list">
          <li
            v-for="user in users"
            :key="user.id"
            class="user-list-item"
            :class="{ editing: editingId === user.id, self: isSelf(user) }"
          >
            <div class="user-row-top">
              <div class="user-identity">
                <div class="user-avatar" :class="user.role === 'ADMIN' ? 'admin' : 'user'">
                  {{ avatarLetter(user.username) }}
                </div>
                <div class="user-identity-text">
                  <div class="user-name-row">
                    <strong>{{ user.username }}</strong>
                    <span
                      class="role-badge"
                      :class="user.role === 'ADMIN' ? 'admin' : 'user'"
                    >
                      {{ roleLabel(user.role) }}
                    </span>
                    <span v-if="isSelf(user)" class="self-badge">나</span>
                  </div>
                  <div class="user-meta-row">
                    <span>가입 {{ formatCreatedAt(user.createdAt) }}</span>
                    <span class="user-meta-dot" aria-hidden="true">·</span>
                    <span>ID {{ user.id }}</span>
                  </div>
                </div>
              </div>

              <div v-if="editingId !== user.id" class="inline-actions user-row-actions">
                <button type="button" class="ghost-button" @click="startEdit(user)">
                  수정
                </button>
                <button
                  type="button"
                  class="danger-button"
                  :disabled="isSelf(user) || deletingId === user.id"
                  :title="isSelf(user) ? '자기 자신은 삭제할 수 없습니다.' : undefined"
                  @click="handleDelete(user)"
                >
                  {{ deletingId === user.id ? "삭제 중..." : "삭제" }}
                </button>
              </div>
            </div>

            <form
              v-if="editingId === user.id"
              class="form-grid user-edit-form action-panel"
              @submit.prevent="submitEdit(user)"
            >
              <div class="field">
                <span>역할</span>
                <div class="role-options compact" role="radiogroup" :aria-label="`${user.username} 역할`">
                  <label class="role-option" :class="{ active: editRole === 'USER' }">
                    <input v-model="editRole" type="radio" value="USER" />
                    <span>
                      <strong>일반사용자</strong>
                    </span>
                  </label>
                  <label class="role-option" :class="{ active: editRole === 'ADMIN' }">
                    <input v-model="editRole" type="radio" value="ADMIN" />
                    <span>
                      <strong>관리자</strong>
                    </span>
                  </label>
                </div>
              </div>

              <label class="field">
                <span>새 비밀번호 <em class="optional-hint">(변경할 때만 입력)</em></span>
                <input
                  v-model="editPassword"
                  type="password"
                  autocomplete="new-password"
                  minlength="4"
                  placeholder="비워두면 기존 비밀번호 유지"
                />
              </label>

              <div class="inline-actions user-form-actions">
                <button type="submit" class="submit-button" :disabled="saving">
                  {{ saving ? "저장 중..." : "저장" }}
                </button>
                <button type="button" class="ghost-button" @click="cancelEdit">취소</button>
              </div>
            </form>
          </li>
        </ul>
      </section>
    </section>
  </main>
</template>
