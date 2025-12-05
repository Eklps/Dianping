package com.hmdp.utils;

import cn.hutool.http.HttpStatus;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,Object handler)throws Exception{
        //获取session
        HttpSession session=request.getSession();

        //获取session用户
        Object user= session.getAttribute("user");
        //判断用户是否存在
        if(user==null){
            //不存在，拦截
            response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
            return false;
        }
        //存在，保存用户信息到threadLocal中
        UserHolder.saveUser((UserDTO)user);
        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,Object handler, @Nullable Exception ex) throws Exception{
        UserHolder.removeUser();
    }

}
