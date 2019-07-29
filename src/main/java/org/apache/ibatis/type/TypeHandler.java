/**
 *    Copyright 2009-2019 the original author or authors.
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
package org.apache.ibatis.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 当MyBatis将一个Java对象作为输入/输出参数执行CRUD语句操作时，
 * 它会创建一个PreparedStatement对象，并且调用setXXX()为占位符设置相应的参数值。
 * XXX可以是Int，String，Date等Java内置类型，或者用户自定义的类型。
 * 在实现上，MyBatis是通过使用类型处理器（type handler）来确定XXX是具体什么类型的。
 * MyBatis对于下列类型使用内建的类型处理器：
 * 所有的基本数据类型、
 * 基本类型的包裹类型、
 * byte[] 、
 * java.util.Date、
 * java.sql.Date、
 * java,sql.Time、
 * java.sql.Timestamp、
 * java 枚举类型等。
 * 对于用户自定义的类型，我们可以创建一个自定义的类型处理器。要创建自定义类型处理器，只要实现TypeHandler接口即可，
 * @author Clinton Begin
 */
public interface TypeHandler<T> {

  void setParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException;

  /**
   * @param columnName Colunm name, when configuration <code>useColumnLabel</code> is <code>false</code>
   */
  T getResult(ResultSet rs, String columnName) throws SQLException;

  T getResult(ResultSet rs, int columnIndex) throws SQLException;

  T getResult(CallableStatement cs, int columnIndex) throws SQLException;

}
