package org.ruoyi.common.mybatis.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;

/**
 * 数据库类型
 *
 * @author Lion Li
 */
@Getter
@AllArgsConstructor
public enum DataBaseType {

    /**
     * MySQL
     */
    MY_SQL("MySQL"),

    /**
     * Oracle
     */
    ORACLE("Oracle"),

    /**
     * PostgreSQL
     */
    POSTGRE_SQL("PostgreSQL"),

    /**
     * SQL Server
     */
    SQL_SERVER("Microsoft SQL Server");

    /**
     * 数据库类型
     */
    private final String type;

    /**
     * 根据数据库产品名称查找对应的数据库类型
     * <p>
     * 无法识别时抛异常，不回落到 MySQL。回落到 MySQL 会让连到非 MySQL 库的应用
     * 静默生成 MySQL 方言 SQL（见 DataBaseHelper.findInSet），错误在运行期才暴露且难以定位。
     *
     * @param databaseProductName 数据库产品名称
     * @return 对应的数据库类型枚举值
     * @throws ServiceException 产品名称为空或不在已适配列表内
     */
    public static DataBaseType find(String databaseProductName) {
        if (StringUtils.isBlank(databaseProductName)) {
            throw new ServiceException("无法识别数据库产品类型：连接元数据返回空，拒绝按默认方言执行 SQL");
        }
        for (DataBaseType type : values()) {
            if (type.getType().equals(databaseProductName)) {
                return type;
            }
        }
        throw new ServiceException("尚未适配的数据库产品类型: " + databaseProductName
            + "，支持: " + java.util.Arrays.stream(values()).map(DataBaseType::getType).toList());
    }

    /**
     * 判断是否为 MySQL 类型
     */
    public boolean isMySql() {
        return this == MY_SQL;
    }

    /**
     * 判断是否为 Oracle 类型
     */
    public boolean isOracle() {
        return this == ORACLE;
    }

    /**
     * 判断是否为 PostgreSQL 类型
     */
    public boolean isPostgreSql() {
        return this == POSTGRE_SQL;
    }

    /**
     * 判断是否为 SQL Server 类型
     */
    public boolean isSqlServer() {
        return this == SQL_SERVER;
    }

}
