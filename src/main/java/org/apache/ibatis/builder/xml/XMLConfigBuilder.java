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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;
import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 *
 * XMLConfigBuilder以及解析Mapper文件的XMLMapperBuilder都继承于BaseBuilder。
 * 他们对于XML文件本身技术上的加载和解析都委托给了XPathParser，
 * 最终用的是jdk自带的xml解析器而非第三方比如dom4j，
 * 底层使用了xpath方式进行节点解析。
 * new XPathParser(reader, true, props, new XMLMapperEntityResolver())的参数含义分别是Reader，是否进行DTD 校验，属性配置，XML实体节点解析器。
 */
public class XMLConfigBuilder extends BaseBuilder {

  private boolean parsed;
  private final XPathParser parser;
  private String environment;
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  /**
   * entityResolver比较好理解，跟Spring的XML标签解析器一样，
   * 有默认的解析器，也有自定义的比如tx，dubbo等，主要使用了策略模式，
   * 在这里mybatis硬编码为了XMLMapperEntityResolver。
   * @param reader
   * @param environment
   * @param props
   */
  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  //主要是根据mybatis自身需要创建一个文档解析器，然后调用parse将输入input source解析为DOM XML文档并返回。
  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  //得到XPathParser实例之后，就调用另一个使用XPathParser作为配置来源的重载构造函数了
  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {

    //来看Configuration这个核心类的关键信息，主要是xml配置文件里面的所有配置的实现表示(几乎所有的情况下都要依赖与Configuration这个类)。
    //设置关键配置environment以及properties文件（mybatis在这里的实现和spring的机制有些不同），
    //最后将上面构建的XPathParser设置为XMLConfigBuilder的parser属性值。
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  //XMLConfigBuilder创建完成之后，SqlSessionFactoryBuild调用parser.parse()创建Configuration。
  //所有，真正Configuration构建逻辑就在XMLConfigBuilder.parse()里面，如下所示：

  //首先判断有没有解析过配置文件，只有没有解析过才允许解析。
  // 其中调用了parser.evalNode(“/configuration”)返回根节点的org.apache.ibatis.parsing.XNode表示，
  // XNode里面主要把关键的节点属性和占位符变量结构化出来，后面我们再看。
  public Configuration parse() {
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    //mybatis配置文件解析的主流程
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  //根据mybatis的主要配置进行解析
  //所有的root.evalNode底层都是调用XML DOM的evaluate()方法，
  // 根据给定的节点表达式来计算指定的 XPath 表达式，并且返回一个XPathResult对象，
  // 返回类型在Node.evalNode()方法中均被指定为NODE。
  private void parseConfiguration(XNode root) {
    try {
      //issue #117 read properties first
      //解析properties的方法
      propertiesElement(root.evalNode("properties"));

      Properties settings = settingsAsProperties(root.evalNode("settings"));
      //得到setting之后，调用settingsElement(Properties props)将各值赋值给configuration，
      // 同时在这里有重新设置了默认值，所有这一点很重要，configuration中的默认值不一定是真正的默认值。

      loadCustomVfs(settings); //从配置文件中读取配置的实现类，然后将其进行实例化
      loadCustomLogImpl(settings); //从配置文件中读取配置的日志实现类，然后将其进行实例化
      typeAliasesElement(root.evalNode("typeAliases"));
      pluginElement(root.evalNode("plugins"));
      objectFactoryElement(root.evalNode("objectFactory"));
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      environmentsElement(root.evalNode("environments"));
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      typeHandlerElement(root.evalNode("typeHandlers"));
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  //加载settings节点settingsAsProperties
  /**
   * 首先加载settings下面的setting节点为property，然后检查所有属性,确保它们都在Configuration中已定义，而非未知的设置。
   * 注：MetaClass是一个保存对象定义比如getter/setter/构造器等的元数据类,localReflectorFactory则是mybatis提供的默认反射工厂实现，
   * 这个ReflectorFactory主要采用了工厂类，其内部使用的Reflector采用了facade设计模式，简化反射的使用。
   * @param context
   * @return
   */
  private Properties settingsAsProperties(XNode context) {
    if (context == null) {
      return new Properties();
    }
    Properties props = context.getChildrenAsProperties(); //获取xml文件中的配置节点信息
    // Check that all settings are known to the configuration class
    // 检查所有从settings加载的设置,确保它们都在Configuration定义的范围内，就是别出现configuration类中没有的属性
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    for (Object key : props.keySet()) {
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  /**
   * 加载自定义VFS loadCustomVfs
   *
   * VFS主要用来加载容器内的各种资源，比如jar或者class文件。
   * mybatis提供了2个实现 JBoss6VFS 和 DefaultVFS，并提供了用户扩展点，用于自定义VFS实现，
   * 加载顺序是自定义VFS实现 > 默认VFS实现 取第一个加载成功的，默认情况下会先加载JBoss6VFS，
   * 如果classpath下找不到jboss的vfs实现才会加载默认VFS实现
   * @param props
   * @throws ClassNotFoundException
   */
  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  private void loadCustomLogImpl(Properties props) {
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
    configuration.setLogImpl(logImpl);
  }


  /**
   * 解析类型别名typeAliasesElement
   * mybatis主要提供两种类型的别名设置，具体类的别名以及包的别名设置。
   * 类型别名是为 Java 类型设置一个短的名字，存在的意义仅在于用来减少类完全限定名的冗余。
   *
   * 一种是直接扫描<typeAlias alias="Blog" type="domain.blog.Blog"/>这样的标签
   * 另一种是配置
   * <typeAliases>
   *   <package name="domain.blog"/>
   * </typeAliases>
   */
  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        //在没有注解的情况下，会使用 Bean的首字母小写的非限定类名来作为它的别名。
        // 比如 domain.blog.Author 的别名为author；若有注解，则别名为其注解值。
        // 所以所有的别名，无论是内置的还是自定义的，都一开始被保存在configuration.typeAliasRegistry中了
        if ("package".equals(child.getName())) {
          String typeAliasPackage = child.getStringAttribute("name");
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {
          String alias = child.getStringAttribute("alias");
          String type = child.getStringAttribute("type");
          try {
            Class<?> clazz = Resources.classForName(type);
            if (alias == null) {
              typeAliasRegistry.registerAlias(clazz);
            } else {
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  /**
   * 加载插件pluginElement
   * mybatis调用pluginElement(root.evalNode(“plugins”));加载mybatis插件，
   * 最常用的插件应该算是分页插件PageHelper了，再比如druid连接池提供的各种监控、拦截、预发检查功能，
   * 在使用其它连接池比如dbcp的时候，在不修改连接池源码的情况下，就可以借助mybatis的插件体系实现
   *
   * 插件在具体实现的时候，采用的是拦截器模式，
   * 要注册为mybatis插件，必须实现org.apache.ibatis.plugin.Interceptor接口，每个插件可以有自己的属性。
   * interceptor属性值既可以完整的类名，也可以是别名，只要别名在typealias中存在即可，
   * 如果启动时无法解析，会抛出ClassNotFound异常。
   * 实例化插件后，将设置插件的属性赋值给插件实现类的属性字段
   */
  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        String interceptor = child.getStringAttribute("interceptor");
        Properties properties = child.getChildrenAsProperties();
        //将interceptor指定的名称解析为Interceptor类型
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
        interceptorInstance.setProperties(properties);
        configuration.addInterceptor(interceptorInstance);//将插件添加到configuration类中
      }
    }
  }

  /**
   * 加载对象工厂objectFactoryElement
   * MyBatis 每次创建结果对象的新实例时，它都会使用一个对象工厂（ObjectFactory）实例来完成。
   * 默认的对象工厂DefaultObjectFactory做的仅仅是实例化目标类，
   * 要么通过默认构造方法，要么在参数映射存在的时候通过参数构造方法来实例化
   *
   * 无论是创建集合类型、Map类型还是其他类型，都素hi同样的处理方式。
   * 如果想覆盖对象工厂的默认行为，则可以通过创建自己的对象工厂来实现。
   * ObjectFactory 接口很简单，它包含两个创建用的方法，一个是处理默认构造方法的，另外一个是处理带参数的构造方法的。
   * 最后，setProperties 方法可以被用来配置 ObjectFactory，
   * 在初始化你的 ObjectFactory 实例后，objectFactory元素体中定义的属性会被传递给setProperties方法
   * <objectFactory type="org.mybatis.example.ExampleObjectFactory">
   *   <property name="someProperty" value="100"/>
   * </objectFactory>
   */
  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties properties = context.getChildrenAsProperties();
      ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
      factory.setProperties(properties);
      configuration.setObjectFactory(factory);
    }
  }

  /**
   * 创建对象包装器工厂objectWrapperFactoryElement
   * 对象包装器工厂主要用来包装返回result对象，比如说可以用来设置某些敏感字段脱敏或者加密等
   * BeanWrapper是BaseWrapper的默认实现
   */
  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      //要实现自定义的对象包装器工厂，只要实现ObjectWrapperFactory中的两个接口hasWrapperFor和getWrapperFor即可
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
      configuration.setObjectWrapperFactory(factory);
    }
  }

  /**
   * 加载反射工厂reflectorFactoryElement
   * 因为加载配置文件中的各种插件类等等，为了提供更好的灵活性，mybatis支持用户自定义反射工厂，
   * 不过总体来说，用的不多，要实现反射工厂，只要实现ReflectorFactory接口即可。
   * 默认的反射工厂是DefaultReflectorFactory。一般来说，使用默认的反射工厂就可以了。
   * @param context
   * @throws Exception
   */
  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
      configuration.setReflectorFactory(factory);
    }
  }

  /**首先加载properties节点下的property属性，
     然后从url或resource加载配置文件，都先和configuration.variables合并，
     然后赋值到XMLConfigBuilder.parser和BaseBuilder.configuration。
     此时开始所有的属性就可以在随后的整个配置文件中使用了。
  */
  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
      // 加载property节点为property
      Properties defaults = context.getChildrenAsProperties();
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      // 必须至少包含resource或者url属性之一
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      if (resource != null) {
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      parser.setVariables(defaults);
      configuration.setVariables(defaults);
    }
  }

  private void settingsElement(Properties props) {
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setDefaultResultSetType(resolveResultSetType(props.getProperty("defaultResultSetType")));
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
  }

  /**
   * 加载环境配置environmentsElement
   *
   * 环境可以说是mybatis-config配置文件中最重要的部分，它类似于spring和maven里面的profile，
   * 允许给开发、生产环境同时配置不同的environment，根据不同的环境加载不同的配置，这也是常见的做法，
   * 如果在SqlSessionFactoryBuilder调用期间没有传递使用哪个环境的话，
   * 默认会使用一个名为default”的环境。找到对应的environment之后，就可以加载事务管理器和数据源了。
   *
   * 事务管理器和数据源类型这里都用到了类型别名，JDBC/POOLED都是在mybatis内置提供的，
   * 在Configuration构造器执行期间注册到TypeAliasRegister。
   *
   * mybatis内置提供JDBC和MANAGED两种事务管理方式，前者主要用于简单JDBC模式，后者主要用于容器管理事务，
   * 一般使用JDBC事务管理方式。mybatis内置提供JNDI、POOLED、UNPOOLED三种数据源工厂，一般情况下使用POOLED数据源。
   *
   */
  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      if (environment == null) {
        environment = context.getStringAttribute("default");
      }
      for (XNode child : context.getChildren()) {
        String id = child.getStringAttribute("id");
        //查找匹配的environment
        if (isSpecifiedEnvironment(id)) {
          // 事务配置并创建事务工厂
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          // 数据源配置加载并实例化数据源, 数据源是必备的
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          DataSource dataSource = dsFactory.getDataSource();
          // 创建Environment.Builder
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          configuration.setEnvironment(environmentBuilder.build());
        }
      }
    }
  }

  /**
   * 数据库厂商标识加载databaseIdProviderElement
   *
   * MyBatis 可以根据不同的数据库厂商执行不同的语句，这种多厂商的支持是基于映射语句中的 databaseId 属性。
   * MyBatis 会加载不带 databaseId 属性和带有匹配当前数据库 databaseId 属性的所有语句。
   * 如果同时找到带有 databaseId 和不带 databaseId 的相同语句，则后者会被舍弃。
   *
   * <databaseIdProvider type="DB_VENDOR" />
   * 这里的 DB_VENDOR 会通过 DatabaseMetaData#getDatabaseProductName() 返回的字符串进行设置。
   * 由于通常情况下这个字符串都非常长而且相同产品的不同版本会返回不同的值，所以最好通过设置属性别名来使其变短
   *<databaseIdProvider type="DB_VENDOR">
   *   <property name="SQL Server" value="sqlserver"/>
   *   <property name="MySQL" value="mysql"/>
   *   <property name="Oracle" value="oracle" />
   * </databaseIdProvider>
   *
   *
   */
  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      if ("VENDOR".equals(type)) {
        type = "DB_VENDOR";
      }
      Properties properties = context.getChildrenAsProperties();
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
      databaseIdProvider.setProperties(properties);
    }
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      configuration.setDatabaseId(databaseId);
    }
  }

  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  /**
   * 加载类型处理器typeHandlerElement
   *
   * 无论是 MyBatis 在预处理语句（PreparedStatement）中设置一个参数时，还是从结果集中取出一个值时，
   * 都会用类型处理器将获取的值以合适的方式转换成 Java 类型。
   *
   * mybatis提供了两种方式注册类型处理器，package自动检索方式和显示定义方式。
   * 使用自动检索（autodiscovery）功能的时候，只能通过注解方式来指定 JDBC 的类型。
   *
   * 为了简化使用，mybatis在初始化TypeHandlerRegistry期间，自动注册了大部分的常用的类型处理器比如字符串、数字、日期等。
   * 对于非标准的类型，用户可以自定义类型处理器来处理。
   * 要实现一个自定义类型处理器，只要实现 org.apache.ibatis.type.TypeHandler 接口，
   * 或继承一个实用类 org.apache.ibatis.type.BaseTypeHandler， 并将它映射到一个 JDBC 类型即可。
   *
   * 要注意 MyBatis 不会窥探数据库元信息来决定使用哪种类型，
   * 所以你必须在参数和结果映射中指明那是 VARCHAR 类型的字段， 以使其能够绑定到正确的类型处理器上。
   * 这是因为：MyBatis 直到语句被执行才清楚数据类型。
   */
  private void typeHandlerElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        if ("package".equals(child.getName())) {
          String typeHandlerPackage = child.getStringAttribute("name");
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  /**
   * 加载mapper文件mapperElement
   * mapper文件是mybatis框架的核心之处，所有的用户sql语句都编写在mapper文件中，
   * 所以理解mapper文件对于所有的开发人员来说都是必备的要求。
   *
   * mybatis提供了两类配置mapper的方法，第一类是使用package自动搜索的模式，这样指定package下所有接口都会被注册为mapper，
   * <mappers>
   *   <package name="org.mybatis.builder"/>
   * </mappers>
   *
   * 另外一类是明确指定mapper，这又可以通过resource、url或者class进行细分。
   * <mappers>
   *   <mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
   *   <mapper resource="org/mybatis/builder/BlogMapper.xml"/>
   *   <mapper resource="org/mybatis/builder/PostMapper.xml"/>
   * </mappers>
   * <mappers>
   *   <mapper url="file:///var/mappers/AuthorMapper.xml"/>
   *   <mapper url="file:///var/mappers/BlogMapper.xml"/>
   *   <mapper url="file:///var/mappers/PostMapper.xml"/>
   * </mappers>
   * <mappers>
   *   <mapper class="org.mybatis.builder.AuthorMapper"/>
   *   <mapper class="org.mybatis.builder.BlogMapper"/>
   *   <mapper class="org.mybatis.builder.PostMapper"/>
   * </mappers>
   *
   * 需要注意的是，如果要同时使用package自动扫描和通过mapper明确指定要加载的mapper，
   * 则必须先声明mapper，然后声明package，否则DTD校验会失败。
   * 同时一定要确保package自动扫描的范围不包含明确指定的mapper，
   * 否则在通过package扫描的interface的时候，尝试加载对应xml文件的loadXmlResource()的逻辑中出现判重出错，
   * 报org.apache.ibatis.binding.BindingException异常。
   *
   * 对于通过package加载的mapper文件，调用mapperRegistry.addMappers(packageName);进行加载，其核心逻辑在org.apache.ibatis.binding.MapperRegistry中，对于每个找到的接口或者mapper文件，最后调用用XMLMapperBuilder进行具体解析。
   * 对于明确指定的mapper文件或者mapper接口，则主要使用XMLMapperBuilder进行具体解析。
   *
   *
   * mapper加载与初始化
   * 前面说过mybatis mapper文件的加载主要有两大类，通过package加载和明确指定的方式。
   *
   * 一般来说，对于简单语句来说，使用注解代码会更加清晰，然而Java注解对于复杂语句比如同时包含了构造器、鉴别器、resultMap来说就会非常混乱，应该限制使用，此时应该使用XML文件，
   */
  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        // 如果要同时使用package自动扫描和通过mapper明确指定要加载的mapper，
        // 一定要确保package自动扫描的范围不包含明确指定的mapper，
        // 否则在通过package扫描的interface的时候，
        // 尝试加载对应xml文件的loadXmlResource()的逻辑中出现判重出错，
        // 报org.apache.ibatis.binding.BindingException异常，
        // 即使xml文件中包含的内容和mapper接口中包含的语句不重复也会出错，
        // 包括加载mapper接口时自动加载的xml mapper也一样会出错。

        if ("package".equals(child.getName())) {
          String mapperPackage = child.getStringAttribute("name");
          configuration.addMappers(mapperPackage);
        } else {
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            InputStream inputStream = Resources.getResourceAsStream(resource);

            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
            mapperParser.parse();
          } else if (resource == null && url != null && mapperClass == null) {
            ErrorContext.instance().resource(url);
            InputStream inputStream = Resources.getUrlAsStream(url);

            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
            mapperParser.parse();
          } else if (resource == null && url == null && mapperClass != null) {
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    } else if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    } else if (environment.equals(id)) {
      return true;
    }
    return false;
  }

}
