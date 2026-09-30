package org.ruoyi.common.mybatis.enums;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.exception.ServiceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 方言识别测试。锁住的行为是：无法识别的数据库必须拒绝，不能回落到 MySQL 方言。
 * <p>
 * 本仓库 surefire 配置按 profiles.active 过滤 @Tag，故 -Pdev 下执行需带 dev 标签。
 */
@Tag("dev")
class DataBaseTypeTest {

    @Test
    void shouldResolveSupportedProducts() {
        assertEquals(DataBaseType.MY_SQL, DataBaseType.find("MySQL"));
        assertEquals(DataBaseType.POSTGRE_SQL, DataBaseType.find("PostgreSQL"));
        assertEquals(DataBaseType.ORACLE, DataBaseType.find("Oracle"));
        assertEquals(DataBaseType.SQL_SERVER, DataBaseType.find("Microsoft SQL Server"));
    }

    @Test
    void shouldRejectUnknownProductInsteadOfMySQLFallback() {
        ServiceException ex = assertThrows(ServiceException.class, () -> DataBaseType.find("CockroachDB"));
        assertEquals(true, ex.getMessage().contains("CockroachDB"));
    }

    @Test
    void shouldRejectBlankProduct() {
        assertThrows(ServiceException.class, () -> DataBaseType.find(null));
        assertThrows(ServiceException.class, () -> DataBaseType.find("  "));
    }
}
