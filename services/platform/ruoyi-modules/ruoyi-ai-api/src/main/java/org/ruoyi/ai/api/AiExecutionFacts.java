package org.ruoyi.ai.api;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 执行主体的<b>业务事实</b>（不含传输认证）。
 *
 * <p>内嵌后的唯一身份口径。原 P1 的 {@code ExecutionPrincipal} 把两类东西混在一个记录里：
 * <ul>
 *   <li>业务事实：租户、用户、成员、scopes、policyVersion、aclVersion——本地调用同样需要；</li>
 *   <li>传输事实：jti、issuer、iat、exp——只服务于原跨服务委托 JWT。</li>
 * </ul>
 * 本地路径没有 JWT，也不应该为了满足旧构造器去伪造一个（V2 §5.1）。因此本模块只承载业务事实，
 * 传输字段留在退役中的 {@code AiGatewayClient} 一侧。
 *
 * <p>校验口径保持与 P1 一致，并在这里集中实现一次，避免两侧各写一遍后漂移：
 * <ul>
 *   <li>{@code tenantId}：1..64 字符，不允许冒号、不允许首尾空白（出现冒号说明来源不满足契约，
 *       必须拒绝而不是截断或回落到默认租户）；</li>
 *   <li>{@code userId}：platform 的十进制 user id，1..20 字符；</li>
 *   <li>{@code membershipId}：canonical {@code platform:<tenantId>:<userId>}，且必须与两者一致；</li>
 *   <li>{@code policyVersion}/{@code aclVersion}：均 ≥ 1；缺任一即拒绝，不允许"没有版本就当有效"。</li>
 * </ul>
 *
 * <p>本记录只能由身份端口或在线判定组件构造。请求 body/header 里的 tenantId、userId、
 * membershipId <b>不参与</b>主体选择。
 */
public record AiExecutionFacts(
        String tenantId,
        String userId,
        String membershipId,
        int policyVersion,
        int aclVersion,
        Set<String> scopes) {

    /** tenantId 上限，与 P1 契约一致。 */
    public static final int MAX_TENANT_ID_LENGTH = 64;

    /** userId 上限：platform user_id 是 int8，十进制最长 20 位。 */
    public static final int MAX_USER_ID_LENGTH = 20;

    /** membershipId 列宽（varchar(160)），与统一库中的 ai_* 表一致。 */
    public static final int MAX_MEMBERSHIP_ID_LENGTH = 160;

    /** canonical membership 前缀。 */
    public static final String MEMBERSHIP_PREFIX = "platform:";

    public AiExecutionFacts {
        requireTenantId(tenantId);
        requireUserId(userId);
        requireMembershipId(membershipId, tenantId, userId);
        if (policyVersion < 1) {
            throw new IllegalArgumentException("policyVersion must be >= 1");
        }
        if (aclVersion < 1) {
            throw new IllegalArgumentException("aclVersion must be >= 1");
        }
        scopes = scopes == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(scopes));
    }

    /**
     * 由租户与用户构造 canonical 成员标识。调用方不得自行拼接字符串。
     */
    public static String membershipOf(String tenantId, String userId) {
        return MEMBERSHIP_PREFIX + tenantId + ":" + userId;
    }

    /** 权限集合中是否包含指定权限（精确相等，不做通配）。 */
    public boolean hasScope(String scope) {
        return scope != null && scopes.contains(scope);
    }

    static void requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (tenantId.length() > MAX_TENANT_ID_LENGTH) {
            throw new IllegalArgumentException("tenantId exceeds " + MAX_TENANT_ID_LENGTH + " characters");
        }
        if (tenantId.indexOf(':') >= 0) {
            throw new IllegalArgumentException("tenantId must not contain ':'");
        }
        if (!tenantId.equals(tenantId.trim())) {
            throw new IllegalArgumentException("tenantId must not have surrounding whitespace");
        }
    }

    static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (userId.length() > MAX_USER_ID_LENGTH) {
            throw new IllegalArgumentException("userId exceeds " + MAX_USER_ID_LENGTH + " characters");
        }
        for (int i = 0; i < userId.length(); i++) {
            if (userId.charAt(i) < '0' || userId.charAt(i) > '9') {
                throw new IllegalArgumentException("userId must be a decimal string");
            }
        }
    }

    static void requireMembershipId(String membershipId, String tenantId, String userId) {
        if (membershipId == null || membershipId.isBlank()) {
            throw new IllegalArgumentException("membershipId is required");
        }
        if (membershipId.length() > MAX_MEMBERSHIP_ID_LENGTH) {
            throw new IllegalArgumentException("membershipId exceeds " + MAX_MEMBERSHIP_ID_LENGTH + " characters");
        }
        String expected = membershipOf(tenantId, userId);
        if (!expected.equals(membershipId)) {
            throw new IllegalArgumentException("membershipId must equal " + expected);
        }
    }
}
