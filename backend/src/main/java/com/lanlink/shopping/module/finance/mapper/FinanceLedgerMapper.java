package com.lanlink.shopping.module.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.finance.entity.FinanceLedger;

/**
 * 财务台账访问（t_finance_ledger）。append-only：只 INSERT 与查询，无 UPDATE/DELETE 方法。
 */
public interface FinanceLedgerMapper extends BaseMapper<FinanceLedger> {
}
