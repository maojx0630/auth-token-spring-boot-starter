package com.github.maojx0630.auth_token;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.*;
import cn.hutool.crypto.asymmetric.Sign;
import cn.hutool.crypto.asymmetric.SignAlgorithm;
import com.alibaba.ttl.TransmittableThreadLocal;
import com.github.maojx0630.auth_token.store.TokenStoreInterface;
import com.github.maojx0630.auth_token.user.model.AuthTokenRes;
import com.github.maojx0630.auth_token.user.model.LoginParam;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Optional;

/**
 * @author 毛家兴
 * @since 2022/10/18 14:27
 */
public abstract class AuthTokenUtil {

  /** 签名及校验工具 */
  private static Sign sign;

  /** 配置文件 */
  private static AuthTokenConfig config;

  /** token存储 */
  private static TokenStoreInterface tokenStore;

  /** THREAD_LOCAL */
  private static final TransmittableThreadLocal<AuthTokenRes> THREAD_LOCAL =
      new TransmittableThreadLocal<>();

  private AuthTokenUtil() {}

  /** 用户登录功能 */
  public static AuthTokenRes login(String id) {
    return login(id, null);
  }

  /**
   * 用户登录功能
   *
   * @param id 用户id
   * @param param 补充参数
   * @return com.github.maojx0630.auth_token.user.model.AuthTokenRes
   * @author 毛家兴
   * @since 2022/10/19 14:08
   */
  public static AuthTokenRes login(String id, LoginParam param) {
    param = Optional.ofNullable(param).orElse(new LoginParam()).build();
    AuthTokenRes res = new AuthTokenRes();
    res.setId(id);
    res.setTimeout(param.getTimeout());
    res.setLoginTime(System.currentTimeMillis());
    res.setLastAccessTime(System.currentTimeMillis());
    res.setDeviceType(param.getDeviceType());
    res.setDeviceName(param.getDeviceName());
    res.setTokenKey(res.getDeviceType() + "#" + IdUtil.fastSimpleUUID());
    res.setUserKey(config.getRedisHead() + "_" + res.getUserType() + "_" + res.getId());
    res.setToken(generateToken(res.getUserKey(), res.getTokenKey()));
    setThreadLocal(res);
    tokenStore.put(res.getUserKey(), res.getTokenKey(), res);
    return res;
  }

  /**
   * 退出
   *
   * @author 毛家兴
   * @since 2022/10/19 14:08
   */
  public static void logout() {
    AuthTokenRes authTokenRes = THREAD_LOCAL.get();
    kickOutToken(authTokenRes.getUserKey(), authTokenRes.getTokenKey());
  }

  /**
   * 踢出一个用户的所有登录
   *
   * @param userKey 用户key
   * @author 毛家兴
   * @since 2022/10/19 14:09
   */
  public static void kickOutUser(String userKey) {
    tokenStore.removeUser(userKey);
  }

  /**
   * 踢出一个用户的指定token
   *
   * @param userKey 用户key
   * @param tokenKey token key
   * @author 毛家兴
   * @since 2022/10/19 14:09
   */
  public static void kickOutToken(String userKey, String tokenKey) {
    tokenStore.removeToken(userKey, tokenKey);
  }

  /**
   * 清理用户所有登录信息
   *
   * @author 毛家兴
   * @since 2022/10/19 14:10
   */
  public static void clearAllUser() {
    tokenStore.clearAllUser();
  }

  /* 非对外提供静态方法 */

  /**
   * 校验token是否正确
   *
   * @param token 前端传入token
   * @author 毛家兴
   * @since 2022/10/19 14:09
   */
  static boolean verifyToken(String token) {
    try {
      String str = Base64.decodeStr(token);
      List<String> split = StrUtil.split(str, "&&");
      String data = split.get(0);
      String signHex = split.get(1);
      if (!sign.verify(
          StrUtil.bytes(data, CharsetUtil.CHARSET_UTF_8), HexUtil.decodeHex(signHex))) {
        return false;
      }
      List<String> list = StrUtil.split(data, "_");
      AuthTokenRes authTokenRes = tokenStore.get(list.get(0), list.get(1));
      if (authTokenRes == null) {
        return false;
      } else {
        setThreadLocal(authTokenRes);
        return true;
      }
    } catch (Exception e) {
      return false;
    }
  }

  static void removeThreadLocal() {
    THREAD_LOCAL.remove();
  }

  private static void setThreadLocal(AuthTokenRes authTokenRes) {
    THREAD_LOCAL.set(authTokenRes);
  }

  /** 生成token */
  private static String generateToken(String userKey, String tokenKey) {
    String randomString = RandomUtil.randomString(RandomUtil.randomInt(10, 20));
    String data = userKey + "_" + tokenKey + "_" + randomString;
    return Base64.encode(data + "&&" + sign.signHex(data));
  }

  /** 初始化配置 */
  static void initConfig(AuthTokenConfig authTokenConfig, ApplicationContext applicationContext) {
    config = authTokenConfig;
    sign =
        new Sign(
            SignAlgorithm.SHA256withRSA,
            config.getSignPrivateKeyBase64(),
            config.getSignPublicKeyBase64());
    tokenStore = applicationContext.getBean(TokenStoreInterface.class);
  }
}