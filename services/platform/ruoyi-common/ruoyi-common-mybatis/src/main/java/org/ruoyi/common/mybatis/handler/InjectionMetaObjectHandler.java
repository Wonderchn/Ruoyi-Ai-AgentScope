package org.ruoyi.common.mybatis.handler;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.ruoyi.common.core.domain.model.LoginUser;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.ObjectUtils;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;
import org.ruoyi.common.mybatis.core.identity.PlatformMembershipId;
import org.ruoyi.common.mybatis.core.identity.PlatformMergedRow;
import org.ruoyi.common.satoken.utils.LoginHelper;

import java.util.Date;

/**
 * MP注入处理器
 *
 * @author Lion Li
 * @date 2021/4/25
 */
@Slf4j
public class InjectionMetaObjectHandler implements MetaObjectHandler {

    /**
     * 如果用户不存在默认注入-1代表无用户
     */
    private static final Long DEFAULT_USER_ID = -1L;

    /**
     * 插入填充方法，用于在插入数据时自动填充实体对象中的创建时间、更新时间、创建人、更新人等信息
     *
     * @param metaObject 元对象，用于获取原始对象并进行填充
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        try {
            fillMergedTableIdentity(metaObject);
            if (ObjectUtil.isNotNull(metaObject) && metaObject.getOriginalObject() instanceof BaseEntity baseEntity) {
                // 获取当前时间作为创建时间和更新时间，如果创建时间不为空，则使用创建时间，否则使用当前时间
                Date current = ObjectUtils.notNull(baseEntity.getCreateTime(), new Date());
                baseEntity.setCreateTime(current);
                baseEntity.setUpdateTime(current);

                // 如果创建人为空，则填充当前登录用户的信息
                if (ObjectUtil.isNull(baseEntity.getCreateBy())) {
                    LoginUser loginUser = getLoginUser();
                    if (ObjectUtil.isNotNull(loginUser)) {
                        Long userId = loginUser.getUserId();
                        // 填充创建人、更新人和创建部门信息
                        baseEntity.setCreateBy(userId);
                        baseEntity.setUpdateBy(userId);
                        baseEntity.setCreateDept(ObjectUtils.notNull(baseEntity.getCreateDept(), loginUser.getDeptId()));
                    } else {
                        // 填充创建人、更新人和创建部门信息
                        baseEntity.setCreateBy(DEFAULT_USER_ID);
                        baseEntity.setUpdateBy(DEFAULT_USER_ID);
                        baseEntity.setCreateDept(ObjectUtils.notNull(baseEntity.getCreateDept(), DEFAULT_USER_ID));
                    }
                }
            } else {
                Date date = new Date();
                this.strictInsertFill(metaObject, "createTime", Date.class, date);
                this.strictInsertFill(metaObject, "updateTime", Date.class, date);
            }
        } catch (Exception e) {
            throw new ServiceException("自动注入异常 => " + e.getMessage(), HttpStatus.HTTP_UNAUTHORIZED);
        }
    }

    /**
     * 统一库合并表的平台身份列填充（E5/WP-026）。
     *
     * <p>与创建人/创建部门不同，身份列<b>没有匿名兜底</b>：{@code ai_conversation.member_id}
     * 等是 {@code NOT NULL} 且无列默认值的成员列，用 {@code -1} 之类的默认值会静默把行归到
     * 一个人造成员名下。因此这里缺主体就抛异常，让写路径显式失败。
     *
     * <p>只对实现 {@link PlatformMergedRow} 的实体生效：集合是编译期可见的，漏标一个实体会让
     * 它继续走老路（插入被数据库 NOT NULL 拒绝），而不是被悄悄跳过。
     */
    private void fillMergedTableIdentity(MetaObject metaObject) {
        if (ObjectUtil.isNull(metaObject)
                || !(metaObject.getOriginalObject() instanceof PlatformMergedRow row)) {
            return;
        }
        // 身份没有匿名兜底：缺登录主体就抛，让写路径显式失败
        row.setTenantId(PlatformMembershipId.requireCurrentTenantId());
        row.setMemberId(PlatformMembershipId.requireCurrentMembershipId());
    }

    /**
     * 更新填充方法，用于在更新数据时自动填充实体对象中的更新时间和更新人信息
     *
     * @param metaObject 元对象，用于获取原始对象并进行填充
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        try {
            if (ObjectUtil.isNotNull(metaObject) && metaObject.getOriginalObject() instanceof BaseEntity baseEntity) {
                // 获取当前时间作为更新时间，无论原始对象中的更新时间是否为空都填充
                Date current = new Date();
                baseEntity.setUpdateTime(current);

                // 获取当前登录用户的ID，并填充更新人信息
                Long userId = LoginHelper.getUserId();
                if (ObjectUtil.isNotNull(userId)) {
                    baseEntity.setUpdateBy(userId);
                } else {
                    baseEntity.setUpdateBy(DEFAULT_USER_ID);
                }
            } else {
                this.strictUpdateFill(metaObject, "updateTime", Date.class, new Date());
            }
        } catch (Exception e) {
            throw new ServiceException("自动注入异常 => " + e.getMessage(), HttpStatus.HTTP_UNAUTHORIZED);
        }
    }

    /**
     * 获取当前登录用户信息
     *
     * @return 当前登录用户的信息，如果用户未登录则返回 null
     */
    private LoginUser getLoginUser() {
        LoginUser loginUser;
        try {
            loginUser = LoginHelper.getLoginUser();
        } catch (Exception e) {
            return null;
        }
        return loginUser;
    }

}
