package com.example.todo.todoapi.repository;

import com.example.todo.todoapi.entity.Todo;
import com.example.todo.userapi.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ITodoRepository
        extends JpaRepository<Todo, String> {
    // 특정 회원의 할 일 목록 리턴
    @Query("SELECT t FROM Todo t WHERE t.user = :user")
    List<Todo> findAllByUser(@Param("user") User user);
}
