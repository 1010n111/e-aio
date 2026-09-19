/**
 * 应用壳根包：唯一启动类所在处，也是唯一的装配方。
 *
 * <p>应用壳是 Spring Modulith 的模块之一（显式声明，不依赖"启动类所在包自动成为模块"的隐式行为）；
 * P1 起新增的业务模块各自在根包声明自身模块，应用壳不重复登记它们。业务代码不得进入本包。
 *
 * <p>Modulith 校验（{@code ApplicationModules.verify()}）与跨模块架构规则的唯一落点是本模块。
 */
package com.eaio;
