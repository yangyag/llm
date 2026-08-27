export const CLIPBOARD_UNAVAILABLE = "CLIPBOARD_UNAVAILABLE";

export const CLIPBOARD_SECURE_CONTEXT_MESSAGE =
  "보안 연결(HTTPS)에서만 복사할 수 있습니다.";

export async function writeClipboardText(text: string): Promise<void> {
  const clipboard = window.navigator.clipboard;
  if (!window.isSecureContext || !clipboard?.writeText) {
    throw new Error(CLIPBOARD_UNAVAILABLE);
  }
  await clipboard.writeText(text);
}

export function clipboardUserMessage(err: unknown, fallback: string): string {
  return err instanceof Error && err.message === CLIPBOARD_UNAVAILABLE
    ? CLIPBOARD_SECURE_CONTEXT_MESSAGE
    : fallback;
}
