package com.liang;

import java.util.List;

public interface UserMapper {


  List<User> getUsers();

  User getUserById(String userId);
}
