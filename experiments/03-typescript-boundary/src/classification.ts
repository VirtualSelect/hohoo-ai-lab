export type Category = "ai-apps" | "llm" | "embodied-ai" | "needs-review";
export type Classification = Readonly<{ category: Category; tags: readonly string[] }>;
const categories = new Set<string>(["ai-apps", "llm", "embodied-ai", "needs-review"]);
const asciiTrim = (s: string) => s.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/gu, "");
function requireValue(ok: unknown, code: string): asserts ok {
  if (!ok) throw new Error(code);
}
function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
// Validates an already-decoded value; cannot recover duplicate keys lost by JSON.parse.
export function validateClassification(value: unknown): Classification {
  requireValue(record(value), "object_required");
  requireValue(Object.keys(value).length === 2 && Object.hasOwn(value, "category") &&
    Object.hasOwn(value, "tags"), "fields_required");
  const { category, tags } = value;
  requireValue(typeof category === "string" && categories.has(category), "unknown_category");
  requireValue(Array.isArray(tags) && tags.length >= 1 && tags.length <= 3, "tags_count");
  const checked: string[] = [];
  for (const tag of tags) {
    requireValue(typeof tag === "string", "tag_string_required");
    requireValue(tag.length > 0 && asciiTrim(tag).length > 0 && asciiTrim(tag) === tag, "tag_whitespace");
    requireValue([...tag].length <= 20, "tag_too_long");
    requireValue(!/[\u0000-\u001f\u007f-\u009f]/u.test(tag), "tag_control_character");
    requireValue(!checked.includes(tag), "duplicate_tag");
    checked.push(tag);
  }
  // Narrow to the closed enum only after the explicit runtime membership check.
  return Object.freeze({ category: category as Category, tags: Object.freeze(checked) });
}
// Only for the flat classification contract. JSON.parse first verifies JSON syntax.
// Scan string tokens with nesting state to reject repeated decoded root property names.
function uniqueRootKeys(raw: string): void {
  const keys = new Set<string>();
  let objects = 0, arrays = 0;
  for (let i = 0; i < raw.length; i++) {
    const char = raw[i];
    if (char === "{") objects++;
    else if (char === "}") objects--;
    else if (char === "[") arrays++;
    else if (char === "]") arrays--;
    else if (char === '"') {
      const start = i++;
      while (i < raw.length) {
        if (raw[i] === "\\") i += 2;
        else if (raw[i] === '"') break;
        else i++;
      }
      let after = i + 1;
      while (/\s/u.test(raw[after] ?? "") && after < raw.length) after++;
      if (objects === 1 && arrays === 0 && raw[after] === ":") {
        const key: string = JSON.parse(raw.slice(start, i + 1));
        requireValue(!keys.has(key), "duplicate_field");
        keys.add(key);
      }
    }
  }
}
export function parseClassification(raw: unknown): Classification {
  requireValue(typeof raw === "string" && raw.length <= 8000, "content_missing_or_too_large");
  const decoded: unknown = JSON.parse(raw);
  uniqueRootKeys(raw);
  return validateClassification(decoded);
}
export function unwrapOneJsonFence(raw: string): string {
  const match = /^\s*\x60\x60\x60json\r?\n([\s\S]*?)\r?\n\x60\x60\x60\s*$/u.exec(raw);
  if (!match) return raw;
  const content = match[1]!;
  requireValue(!content.includes("\x60\x60\x60"), "multiple_fences");
  return content;
}
// Intentionally unsafe baseline for this experiment. Never use on untrusted input.
export function unsafeCast(raw: string): Classification {
  return JSON.parse(raw) as Classification;
}
