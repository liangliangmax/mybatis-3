/**
 *    Copyright 2009-2016 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.plugin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author Clinton Begin
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Intercepts {

  /**
   * 它有一个类型为Signature数组的value属性，如果没有指定，
   * 它会拦截StatementHandler、ResultSetHandler、ParameterHandler和Executor这四个核心接口对象中的所有方法。
   * 如需改变默认行为，可以通过明确设置value的值
   *
   * @Intercepts({@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class}),
   *         @Signature(type = ResultSetHandler.class, method = "handleResultSets", args = {Statement.class})})
   * @return
   */
  Signature[] value();
}

