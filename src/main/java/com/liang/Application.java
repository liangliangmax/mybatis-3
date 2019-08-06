package com.liang;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Application {

  public static void main(String[] args) {
    String resource = "com/liang/Configuration.xml";
    Reader reader;

    try {
      reader = Resources.getResourceAsReader(resource);
      SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);

      SqlSession sqlSession = sqlSessionFactory.openSession();
      try {
        //两种写法
        List<User> list = sqlSession.selectList("com.liang.UserMapper.getUsers");

        System.out.println(list);


        UserMapper userMapper = sqlSession.getMapper(UserMapper.class);

        Map map = new HashMap<>();
        map.put("id","fdsafe2f2f2f2");
        map.put("flag","true");
        System.out.println(userMapper.getUserById(map));
      }catch (Exception e1){
        e1.printStackTrace();
      }finally {
        sqlSession.close();
      }

    }catch (Exception e){
      e.printStackTrace();
    }


  }
}
