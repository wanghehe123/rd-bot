export type AlertRecipientType = "CHAT_ID" | "OPEN_ID";

export interface AlertRecipientInput {
  type: AlertRecipientType;
  value: string;
}

export function normalizeAlertRecipients(
  chatRecipients: string[],
  userRecipients: string[]
): AlertRecipientInput[] {
  return [
    ...normalizeRecipientList("CHAT_ID", "oc_", chatRecipients),
    ...normalizeRecipientList("OPEN_ID", "ou_", userRecipients)
  ];
}

function normalizeRecipientList(
  type: AlertRecipientType,
  prefix: string,
  values: string[]
): AlertRecipientInput[] {
  const unique = new Set<string>();
  for (const rawValue of values) {
    const value = rawValue.trim();
    if (!value) continue;
    if (!value.startsWith(prefix)) {
      throw new Error(`${type === "CHAT_ID" ? "群聊 ID" : "用户 Open ID"} 必须以 ${prefix} 开头`);
    }
    unique.add(value);
  }
  return [...unique].map((value) => ({ type, value }));
}
