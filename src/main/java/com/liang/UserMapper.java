package com.liang;

import java.util.List;
import java.util.Map;

public interface UserMapper {


  List<User> getUsers();

  List<User> getUserById(Map param);

  int addUser(User user);
}
