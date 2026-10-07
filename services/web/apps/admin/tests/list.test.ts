/**
 * 管理端列表逻辑的单元测试。
 *
 * 覆盖三件容易写错、且错了很难查的事：
 * 1. 分页参数夹取（`pageNum: 0` 到底表示第一页还是空结果）；
 * 2. 迟到响应丢弃（退出/切租户后旧响应不能覆盖新数据 —— 计划 §3.11）；
 * 3. 错误消息取用（把后端 `msg` 暴露给用户，而不是"请求失败"）。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  createListState,
  DEFAULT_PAGE_SIZE,
  errorMessageOf,
  isEnabledStatus,
  ListLoadEpoch,
  MAX_PAGE_SIZE,
  normalizePageParams,
  statusTagType,
  totalPages,
} from '../src/utils/list.ts';

describe('normalizePageParams', () => {
  it('缺省时 pageNum=1、pageSize=默认值', () => {
    assert.deepEqual(normalizePageParams(undefined), { pageNum: 1, pageSize: DEFAULT_PAGE_SIZE });
    assert.deepEqual(normalizePageParams({}), { pageNum: 1, pageSize: DEFAULT_PAGE_SIZE });
  });

  it('pageNum 0/负数夹到 1（0 在有的实现里是空结果，不能让它流下去）', () => {
    assert.equal(normalizePageParams({ pageNum: 0 }).pageNum, 1);
    assert.equal(normalizePageParams({ pageNum: -5 }).pageNum, 1);
    assert.equal(normalizePageParams({ pageNum: 1.9 }).pageNum, 1);
  });

  it('pageSize 超过上限被夹住（避免一次拉全库）', () => {
    assert.equal(normalizePageParams({ pageSize: 100000 }).pageSize, MAX_PAGE_SIZE);
  });

  it('pageSize 小于 1 时退回默认值，而不是 0（0 会让任何查询都返回空）', () => {
    assert.equal(normalizePageParams({ pageSize: 0 }).pageSize, DEFAULT_PAGE_SIZE);
    assert.equal(normalizePageParams({ pageSize: -1 }).pageSize, DEFAULT_PAGE_SIZE);
  });

  it('NaN / 非数字退回默认值', () => {
    assert.deepEqual(normalizePageParams({ pageNum: Number.NaN, pageSize: Number.NaN }), {
      pageNum: 1,
      pageSize: DEFAULT_PAGE_SIZE,
    });
  });
});

describe('totalPages', () => {
  it('整除', () => {
    assert.equal(totalPages(20, 10), 2);
  });

  it('有余数要进位（否则最后一页看不到）', () => {
    assert.equal(totalPages(21, 10), 3);
  });

  it('total=0 时是 1 页而不是 0 页（0 页会让分页组件显示空白）', () => {
    assert.equal(totalPages(0, 10), 1);
    assert.equal(totalPages(-3, 10), 1);
  });
});

describe('ListLoadEpoch（迟到响应丢弃）', () => {
  it('开始加载后快照有效', () => {
    const epoch = new ListLoadEpoch();
    assert.equal(epoch.isCurrent(epoch.begin()), true);
  });

  it('第二次加载让第一次的快照失效（防止慢响应覆盖新响应）', () => {
    const epoch = new ListLoadEpoch();
    const first = epoch.begin();
    const second = epoch.begin();
    assert.equal(epoch.isCurrent(first), false);
    assert.equal(epoch.isCurrent(second), true);
  });

  it('reset（退出/切租户）让所有在途快照失效', () => {
    const epoch = new ListLoadEpoch();
    const captured = epoch.begin();
    epoch.reset();
    assert.equal(epoch.isCurrent(captured), false);
  });
});

describe('createListState', () => {
  it('初始：空行、total 0、未加载、无错误、第一页', () => {
    const state = createListState();
    assert.deepEqual(state.rows, []);
    assert.equal(state.total, 0);
    assert.equal(state.loading, false);
    assert.equal(state.error, '');
    assert.equal(state.page.pageNum, 1);
  });

  it('可指定页大小', () => {
    assert.equal(createListState(50).page.pageSize, 50);
  });
});

describe('errorMessageOf', () => {
  it('用 Error.message（PlatformApiError 里带的是后端 msg）', () => {
    assert.equal(errorMessageOf(new Error('企业名称已存在')), '企业名称已存在');
  });

  it('字符串原样用', () => {
    assert.equal(errorMessageOf('网络错误'), '网络错误');
  });

  it('未知形状退回通用文案，不显示 [object Object]', () => {
    assert.equal(errorMessageOf({ code: 1 }), '请求失败');
    assert.equal(errorMessageOf(null), '请求失败');
    // 空的 message 与"根本没有 message"是同一件事：都要退回通用文案，
    // 否则页面上会出现一个什么都没有的错误条。
    // 用 Object.assign 构造空 message（`new Error('')` 是 lint 明确禁止的写法）。
    const blankMessage = Object.assign(new Error('placeholder'), { message: '' });
    assert.equal(errorMessageOf(blankMessage), '请求失败');
  });
});

describe('statusTagType / isEnabledStatus', () => {
  it('\'0\' 正常、\'1\' 停用、其它 unknown（不用默认色冒充正常）', () => {
    assert.equal(statusTagType('0'), 'success');
    assert.equal(statusTagType('1'), 'danger');
    assert.equal(statusTagType(undefined), 'info');
    assert.equal(statusTagType('9'), 'info');
  });

  it('isEnabledStatus 只认 "0"', () => {
    assert.equal(isEnabledStatus('0'), true);
    assert.equal(isEnabledStatus('1'), false);
    assert.equal(isEnabledStatus(undefined), false);
  });
});
