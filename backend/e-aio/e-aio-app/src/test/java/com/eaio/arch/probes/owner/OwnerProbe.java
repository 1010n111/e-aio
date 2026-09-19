package com.eaio.arch.probes.owner;

import com.eaio.arch.probes.internal.InternalProbe;

/**
 * 控制组：模拟"模块外的调用方引用另一模块的内部层"。
 *
 * <p>它必须被 {@code platformInternalIsNotReferenced} 与 {@code platformDoesNotDependOnBusinessModules}
 * 同构的规则判违规——否则这两条规则只是"当前代码恰好合规"的假绿灯。
 */
public class OwnerProbe {

    private final InternalProbe dependency = new InternalProbe();

    public String value() {
        return dependency.value();
    }
}
