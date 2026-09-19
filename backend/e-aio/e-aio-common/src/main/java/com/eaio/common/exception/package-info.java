/**
 * 共享面：异常类型（契约清单 6.2 第二部分）——由全局异常处理器与业务代码共同使用。
 *
 * <p>本包与 {@code com.eaio.common.api} 一起构成**共享面**（P0 册附录 6.2 契约清单）：
 * 应用壳作为装配方需要直接使用（异常处理器要认 BusinessException、演示接口要用 IdGenerator），
 * 因此按 {@code @NamedInterface} 显式暴露，而不是留成"内部实现"。
 * 未标注 @NamedInterface 的包才是不对外承诺的内部实现。
 */
@org.springframework.modulith.NamedInterface("api")
package com.eaio.common.exception;
