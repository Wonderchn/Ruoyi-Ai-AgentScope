package org.ruoyi.aiintegration.identity;

/** 持久 permit 的生产端口；integration 不依赖 system/admin。 */
public interface ProductionAuthorizationProvider {
    record PermitRequest(String tenantId, String subject, String membershipId, int policyVersion,
                         int aclVersion, String action, String resourceRef, String resourceRefsHash,
                         String operationId) { }
    record PermitGrant(String permitId, int policyVersion, String operationId) { }
    record PermitRelease(String tenantId, String membershipId, String permitId, String operationId) { }
    PermitGrant acquire(PermitRequest request);
    void release(PermitRelease request);
}
