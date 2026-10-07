/**
 * `/ai/settings`（运行配置权威分布）页面的可测逻辑。
 *
 * 与 `/ai/models` 共用权限判定与失败归因（`../models/runtimeAdmin`）：
 * 两条页面读的是同一个权威口径（`config.read`），**不各自发明一套解释**。
 */
import type { RuntimeSettingFact, RuntimeSettingsView, RuntimeYamlCatalogView } from '@/api';
import { formatSettingValue } from '../models/runtimeAdmin';

export interface NormalizedSettings {
  authority: string;
  yamlIsRuntimeAuthority: boolean;
  /** 恒为 false（服务端事实）：旧 /chat/config 不影响运行权威。 */
  legacyChatConfigAffectsRuntimeAuthority: boolean;
  /** 无已发布版本时为 false —— 本端点仍 200（它是"说明"而不是"选择"）。 */
  revisionAvailable: boolean;
  revision: RuntimeSettingsView['revision'];
  writableRuntimeFacts: RuntimeSettingFact[];
  displayOnly: RuntimeSettingFact[];
  yamlCatalog: RuntimeYamlCatalogView | null;
  notes: string[];
}

export function normalizeSettings(view: RuntimeSettingsView | null | undefined): NormalizedSettings {
  return {
    authority: view?.authority == null ? '' : String(view.authority),
    yamlIsRuntimeAuthority: view?.yamlIsRuntimeAuthority === true,
    legacyChatConfigAffectsRuntimeAuthority: view?.legacyChatConfigAffectsRuntimeAuthority === true,
    revisionAvailable: view?.revisionAvailable === true,
    revision: view?.revision ?? null,
    writableRuntimeFacts: Array.isArray(view?.writableRuntimeFacts) ? view!.writableRuntimeFacts! : [],
    displayOnly: Array.isArray(view?.displayOnly) ? view!.displayOnly! : [],
    yamlCatalog: view?.yamlCatalog ?? null,
    notes: Array.isArray(view?.notes) ? view!.notes! : [],
  };
}

/** `SettingFact.value` 的展示串（对象 JSON 化；空值 `—`）。 */
export function settingValueLabel(fact: RuntimeSettingFact | null | undefined): string {
  return formatSettingValue(fact?.value);
}

/** 该行是否属于"可写运行事实"（服务端 `writable`；前端不推断）。 */
export function isWritableFact(fact: RuntimeSettingFact | null | undefined): boolean {
  return fact?.writable === true;
}

/** 旧 `/chat/config` 行是否出现在仅展示清单里（用于"不影响运行权威"的可见证据）。 */
export function hasLegacyChatConfigFact(facts: readonly RuntimeSettingFact[]): boolean {
  return facts.some(fact => String(fact?.key ?? '') === 'chat.config');
}

/** YAML 目录里是否存在被误标成运行权威的项（必须恒为 false；出现即页面显式报警）。 */
export function yamlAuthorityViolations(view: RuntimeYamlCatalogView | null | undefined): string[] {
  const models = Array.isArray(view?.models) ? view!.models! : [];
  return models
    .filter(model => model?.runtimeAuthority === true)
    .map(model => String(model?.id ?? ''));
}

/** 表格行：`SettingFact` → `{key, authority, value, detail}`（保持列语义）。 */
export function factRows(facts: readonly RuntimeSettingFact[]): Array<{
  key: string;
  authority: string;
  writable: boolean;
  value: string;
  detail: string;
}> {
  return facts.map(fact => ({
    key: String(fact?.key ?? ''),
    authority: String(fact?.authority ?? ''),
    writable: fact?.writable === true,
    value: settingValueLabel(fact),
    detail: String(fact?.detail ?? ''),
  }));
}
