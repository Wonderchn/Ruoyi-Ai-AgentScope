import { h } from 'vue';
import EchartsRenderer from '@/components/EchartsRenderer/index.vue';
import { parseEChartsOption } from '@/utils/echartsOption';

function isEchartsConfig(code: string): boolean {
  return parseEChartsOption(code) !== null;
}

/**
 * 渲染 ECharts 图表
 */
function renderEcharts(code: string) {
  console.log('[codeXRender] 渲染 echarts，代码长度:', code?.length || 0);
  return h(EchartsRenderer, {
    selfProps: {
      code,
      width: '100%',
      height: '600px', // 增加高度
      theme: 'dark',
    },
    style: {
      width: '100%',
      maxWidth: '100%',
    },
  });
}

/**
 * 渲染普通代码块
 */
function renderCodeBlock(code: string, language: string) {
  console.log('[codeXRender] 渲染代码块，语言:', language, '长度:', code?.length || 0);
  return h('pre', [
    h('code', {
      class: `language-${language}`,
    }, code),
  ]);
}

/**
 * XMarkdown code block renderer
 * API 格式: { [language]: (props: { raw: { content: string } }) => VNode }
 */
export const codeXRender = {
  'echarts': (props: { raw: any }) => {
    const code = props.raw?.content || '';
    console.log('[codeXRender.echarts] 收到代码，长度:', code.length);
    return renderEcharts(code);
  },

  'json': (props: { raw: any }) => {
    const code = props.raw?.content || '';
    console.log('[codeXRender.json] 收到代码，长度:', code.length);
    if (isEchartsConfig(code)) {
      return renderEcharts(code);
    }
    return renderCodeBlock(code, 'json');
  },

  'javascript': (props: { raw: any }) => {
    const code = props.raw?.content || '';
    console.log('[codeXRender.javascript] 收到代码，长度:', code.length);
    if (isEchartsConfig(code)) {
      return renderEcharts(code);
    }
    return renderCodeBlock(code, 'javascript');
  },

  'text': (props: { raw: any }) => {
    const code = props.raw?.content || '';
    if (isEchartsConfig(code)) {
      return renderEcharts(code);
    }
    return renderCodeBlock(code, 'text');
  },

  '': (props: { raw: any }) => {
    const code = props.raw?.content || '';
    if (isEchartsConfig(code)) {
      return renderEcharts(code);
    }
    return renderCodeBlock(code, 'plaintext');
  },
};

export default codeXRender;
