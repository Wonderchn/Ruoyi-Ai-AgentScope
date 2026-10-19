/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.shortdrama.llm;

import com.nageoffer.ai.ragent.shortdrama.dao.DramaRows;

import java.util.List;
import java.util.stream.Collectors;

/**
 * F21 短剧生成提示词。
 *
 * <p><b>输入上限是预算边界，不是格式建议。</b>创意 12000 字符、剧本正文 20000 字符
 * 进入提示词前截断；截断只作用于模型输入，落库仍保存用户/脚本原文。
 * 提示词不包含任何凭据、端点或密钥。
 */
public final class DramaPrompts {

    /** 发给模型的剧本文本上限。 */
    public static final int SCRIPT_INPUT_LIMIT = 20000;

    private static final String SCRIPT_SYSTEM = """
            你是顶级短剧编剧与剧本统筹。根据用户提供的故事资料，创作一部剧情完整、可拍摄、
            具有多场次推进的短剧，而不是只截取结局或高潮片段。

            【输出格式 - 严格按顺序】
            第一行输出一个 JSON 对象（单行，不要换行）：
            {"projectName":"项目名","description":"简介(30-80字)","scriptName":"剧本名","tone":"类型与基调","outlineText":"完整分场大纲(300-800字)"}
            第二行输出 ===SCRIPT===
            之后输出完整剧本正文（1200-2500字）。

            【完整故事弧 - 最高优先级】
            1. 覆盖用户资料中的完整主线，禁止只写最终高潮。
            2. 至少 5 个有不同剧情功能的场次；场次覆盖：人物与困境建立 → 改变契机 →
               发展/训练或关系推进 → 重大挫折或背叛 → 主角自主决定 → 高潮行动 → 结果与主题落点。
            3. 每场必须改变剧情状态：角色目标、阻碍、行动、结果。
            4. 不虚构用户资料之外的重大设定、冠军、背叛原因或人物关系。

            【分场要求】
            - 使用标准场景头：内景/外景 + 具体地点 + 时间。
            - 每场包含：场景建立、明确冲突、角色行动、可见结果、引向下一场的钩子。
            - 台词推动认知、关系、决定或行动变化；心理活动转译为可见动作或简短独白。

            只输出上述格式，不要额外说明。
            """;

    private static final String POLISH_SYSTEM = """
            你是顶级短剧编剧与剧本医生。请在保留既有故事事实、人物关系与结局方向的前提下，
            润色并补全用户给出的剧本：修正场次断裂、补足动机与过渡、强化冲突与钩子、
            统一人物称呼与场景头格式。

            【输出格式 - 严格按顺序】
            第一行输出一个 JSON 对象（单行，不要换行）：
            {"projectName":"项目名","description":"简介(30-80字)","scriptName":"剧本名","tone":"类型与基调","outlineText":"完整分场大纲(300-800字)"}
            第二行输出 ===SCRIPT===
            之后输出润色后的完整剧本正文（1200-2500字）。

            不要输出修改说明、对比或任何额外文字。
            """;

    private static final String ANALYSIS_SYSTEM = """
            你是短剧资产分析师。只分析用户给出的当前项目内已有角色与场景，
            禁止引用未给出的 ID、名称或跨项目素材。
            输出单个 JSON 对象，不要 Markdown 代码围栏，不要额外说明：
            {"characters":[{"id":<给定角色ID>,"analysis":"角色在本剧本中的功能、动机与关系","visualNotes":"可拍摄的视觉特征"}],
             "locations":[{"id":<给定场景ID>,"analysis":"场景在剧本中的功能与氛围","stagingNotes":"可执行的站位与调度建议"}],
             "continuityNotes":"跨场连续性风险",
             "styleNotes":"统一视觉风格建议"}
            没有角色或场景时，对应数组输出空数组；不得编造 ID。
            """;

    private static final String PHOTOGRAPHY_SYSTEM = """
            你是短剧摄影指导。根据给定的分镜场次，为每个 sceneNo 输出可执行的摄影规则。
            输出单个 JSON 对象，不要 Markdown 代码围栏，不要额外说明：
            {"rules":[{"sceneNo":<给定场次序号>,"photographyRules":{"shotType":"景别","cameraMove":"运镜","lighting":"光线","composition":"构图"}}]}
            必须覆盖全部给定 sceneNo，不得新增、遗漏或重复。
            """;

    private static final String ACTING_SYSTEM = """
            你是短剧表演指导。根据给定的分镜场次，为每个 sceneNo 输出可执行的表演指导。
            输出单个 JSON 对象，不要 Markdown 代码围栏，不要额外说明：
            {"rules":[{"sceneNo":<给定场次序号>,"actingNotes":{"emotion":"情绪基调","objective":"角色目标","blocking":"走位","delivery":"台词处理"}}]}
            必须覆盖全部给定 sceneNo，不得新增、遗漏或重复。
            """;

    private DramaPrompts() {
    }

    public static String scriptSystem() {
        return SCRIPT_SYSTEM;
    }

    public static String polishSystem() {
        return POLISH_SYSTEM;
    }

    public static String scriptUser(String projectName, String idea) {
        return "用户期望项目名：" + safe(projectName) + "\n用户创意：\n" + truncate(safe(idea));
    }

    public static String polishUser(String projectName, String scriptText) {
        return "用户期望项目名：" + safe(projectName) + "\n待润色剧本：\n" + truncate(safe(scriptText));
    }

    public static String analysisSystem() {
        return ANALYSIS_SYSTEM;
    }

    public static String analysisUser(String projectName,
                                      String scriptText,
                                      String outlineText,
                                      List<DramaRows.CharacterRow> characters,
                                      List<DramaRows.LocationRow> locations) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目：").append(safe(projectName)).append('\n');
        sb.append("剧本大纲：\n").append(truncate(safe(outlineText))).append('\n');
        sb.append("剧本正文：\n").append(truncate(safe(scriptText))).append('\n');
        sb.append("已有角色：\n");
        if (characters.isEmpty()) {
            sb.append("（无）\n");
        } else {
            sb.append(characters.stream()
                    .map(c -> "- id=" + c.id() + " name=" + safe(c.name())
                            + " introduction=" + truncate(safe(c.introduction())))
                    .collect(Collectors.joining("\n"))).append('\n');
        }
        sb.append("已有场景：\n");
        if (locations.isEmpty()) {
            sb.append("（无）\n");
        } else {
            sb.append(locations.stream()
                    .map(l -> "- id=" + l.id() + " name=" + safe(l.name())
                            + " summary=" + truncate(safe(l.summary())))
                    .collect(Collectors.joining("\n"))).append('\n');
        }
        return sb.toString();
    }

    public static String photographySystem() {
        return PHOTOGRAPHY_SYSTEM;
    }

    public static String actingSystem() {
        return ACTING_SYSTEM;
    }

    public static String sceneRulesUser(String projectName,
                                        String scriptText,
                                        List<DramaRows.StoryboardRow> storyboards,
                                        List<DramaRows.CharacterRow> characters,
                                        List<DramaRows.LocationRow> locations) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目：").append(safe(projectName)).append('\n');
        sb.append("剧本正文：\n").append(truncate(safe(scriptText))).append('\n');
        sb.append("角色：\n");
        sb.append(characters.isEmpty() ? "（无）\n" : characters.stream()
                .map(c -> "- " + safe(c.name()) + "：" + truncate(safe(c.introduction())))
                .collect(Collectors.joining("\n")) + "\n");
        sb.append("场景：\n");
        sb.append(locations.isEmpty() ? "（无）\n" : locations.stream()
                .map(l -> "- " + safe(l.name()) + "：" + truncate(safe(l.summary())))
                .collect(Collectors.joining("\n")) + "\n");
        sb.append("分镜场次：\n");
        sb.append(storyboards.stream()
                .map(s -> "- sceneNo=" + s.sceneNo()
                        + " title=" + safe(s.sceneTitle())
                        + " location=" + safe(s.locationName())
                        + " shotType=" + safe(s.shotType())
                        + " cameraMove=" + safe(s.cameraMove())
                        + " scene=" + truncate(safe(s.sceneText())))
                .collect(Collectors.joining("\n")));
        return sb.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value) {
        return value.length() <= SCRIPT_INPUT_LIMIT ? value : value.substring(0, SCRIPT_INPUT_LIMIT);
    }
}
