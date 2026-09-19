package com.eaio.arch.probes.internal;

/** 控制组目标：模拟"模块内部层"，只能被同模块引用。 */
public class InternalProbe {

    public String value() {
        return "internal";
    }
}
