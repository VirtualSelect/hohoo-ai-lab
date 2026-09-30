import type { Classification } from "./classification.ts";
import { parseClassification } from "./classification.ts";
const value: unknown = { category: "llm", tags: [12] };
// This regression proves callers cannot treat unknown as a validated value.
// @ts-expect-error unknown must be checked at the boundary
const unchecked: Classification = value;
const checked: Classification = parseClassification('{"category":"llm","tags":["Token"]}');
void checked; void unchecked;
