package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号格式
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }

        String code= RandomUtil.randomNumbers(6);//生成六位验证码

        //保存验证码到session
        session.setAttribute(phone,code);

        //模拟发送
        log.debug("向{}发送验证码成功，验证码为{}",phone,code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session ){
        //校验手机号
        String phone=loginForm.getPhone();
            //格式不正确，返回错误信息
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号码格式错误");
        }
        //校验验证码
        //获取session中存的验证码
        Object cacheCode = session.getAttribute(phone);

        String code=loginForm.getCode();//这个是用户输入的
        //如果没有发送验证码，或者session验证码过期，则验证码不一致
        if(cacheCode==null || !cacheCode.toString().equals(code)){
            //不一致报错
            return Result.fail("验证码错误");
        }
        //一致，通过手机号查询用户
        User user=query().eq("phone",phone).one();
        //判断用户是否存在
        if(user==null){
            //不存在，创建新用户
            user = createUserWithPhone(phone);
        }

        //存在&不存在，都要把用户保存到session中
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        return user;
    }
}
