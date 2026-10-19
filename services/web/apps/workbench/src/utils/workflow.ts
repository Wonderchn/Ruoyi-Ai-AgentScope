import type { WfNodeInput, WfNodeInputDef } from '@/api/chat/types';

export function defaultWorkflowInputs(defs: WfNodeInputDef[]): WfNodeInput[] {
  return defs.map(def => ({
    uuid: def.uuid,
    name: def.name,
    content: { title: def.title || def.name, type: def.type, value: def.type === 5 ? false : null },
    required: def.required,
  }));
}

export function buildWorkflowInputs(binding: { startInputs: WfNodeInputDef[]; inputs: WfNodeInput[] }, question: string): WfNodeInput[] {
  const inputs = binding.inputs.length
    ? binding.inputs.map(input => ({ ...input, content: { ...input.content } }))
    : defaultWorkflowInputs(binding.startInputs);
  const firstText = inputs.find(input => input.content.type === 1);
  if (firstText)
    firstText.content.value = question;
  return inputs;
}

export function missingWorkflowInput(input: WfNodeInput): boolean {
  const value = input.content.value;
  return !!input.required && (value == null || (typeof value === 'string' && !value.trim()));
}

/** [DONE] carries the End node's output, even when no node streams text. */
export function workflowFinalText(data: unknown): string | null {
  if (!data || typeof data !== 'object')
    return null;
  const output = (data as Record<string, unknown>).output;
  if (!output || typeof output !== 'object' || !('value' in output))
    return null;
  const value = (output as { value: unknown }).value;
  if (value == null)
    return '';
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}
