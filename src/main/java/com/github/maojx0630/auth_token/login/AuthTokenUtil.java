package com.github.maojx0630.auth_token.login;

import cn.hutool.core.codec.Base62;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.*;
import cn.hutool.crypto.asymmetric.Sign;
import cn.hutool.crypto.asymmetric.SignAlgorithm;
import com.alibaba.ttl.TransmittableThreadLocal;
import com.github.maojx0630.auth_token.config.AuthTokenConfig;
import com.github.maojx0630.auth_token.exception.AuthTokenException;
import com.github.maojx0630.auth_token.model.AuthTokenRes;
import com.github.maojx0630.auth_token.store.TokenStoreInterface;
import org.springframework.context.ApplicationContext;

import java.util.*;

/**
 * @author 毛家兴
 * @since 2022/10/18 14:27
 */
public final class AuthTokenUtil {

  /** THREAD_LOCAL */
  private static final TransmittableThreadLocal<AuthTokenRes> THREAD_LOCAL =
      new TransmittableThreadLocal<>();
  /** 签名及校验工具 */
  private static Sign sign;
  /** 配置文件 */
  private static AuthTokenConfig config;
  /** token存储 */
  private static TokenStoreInterface tokenStore;

  private AuthTokenUtil() {}

  /**
   * 获取登录用户 如果未登录会抛出异常
   *
   * @return com.github.maojx0630.auth_token.model.AuthTokenRes
   * @author 毛家兴
   * @since 2022/10/19 15:06
   */
  public static AuthTokenRes getUser() {
    return getOptUser().orElseThrow(() -> AuthTokenException.of("用户未登录"));
  }

  /**
   * 获取登录用户 可能为空
   *
   * @return java.util.Optional<com.github.maojx0630.auth_token.model.AuthTokenRes>
   * @author 毛家兴
   * @since 2022/10/19 15:06
   */
  public static Optional<AuthTokenRes> getOptUser() {
    return Optional.ofNullable(THREAD_LOCAL.get());
  }

  /**
   * 获取全部用户key
   *
   * @return java.util.Collection<java.lang.String>
   * @author 毛家兴
   * @since 2022/10/25 10:58
   */
  public static Collection<String> getAllUserKey() {
    clearOverdueToken();
    return tokenStore.getAllUserKey();
  }

  /** 获取当前登录用户的全部登录信息 */
  public static Collection<AuthTokenRes> getUserAllDevice() {
    return getUserAllDevice(getUser().getUserKey());
  }

  /** 获取所有用户的登录信息 */
  private static Collection<AuthTokenRes> getUserAllDevice(String userKey) {
    clearOverdueUser(userKey);
    return tokenStore.getUserAll(userKey);
  }

  /** 用户登录功能 */
  public static AuthTokenRes login(String id) {
    return login(id, null);
  }

  /**
   * 用户登录功能
   *
   * @param id 用户id
   * @param param 补充参数
   * @return com.github.maojx0630.auth_token.model.AuthTokenRes
   * @author 毛家兴
   * @since 2022/10/19 14:08
   */
  public static AuthTokenRes login(String id, LoginParam param) {
    // 完善参数信息
    param = Optional.ofNullable(param).orElse(LoginParam.builder().build()).completion(config);
    // 构建登录信息
    AuthTokenRes res = new AuthTokenRes();
    res.setId(id);
    res.setTimeout(param.timeout);
    res.setUserType(param.userType);
    res.setLoginTime(param.loginTime);
    res.setLastAccessTime(System.currentTimeMillis());
    res.setDeviceType(param.deviceType);
    res.setDeviceName(param.deviceName);
    res.setTokenKey(IdUtil.fastSimpleUUID());
    res.setUserKey(config.getRedisHead() + "_" + res.getUserType() + "_" + res.getId());
    res.setToken(generateToken(res.getUserKey(), res.getTokenKey()));
    // 并发登录移除
    if (!config.isConcurrentLogin()) {
      tokenStore.removeUser(res.getUserKey());
    }
    // 同端互斥移除登录
    if (config.isDeviceReject()) {
      Set<String> keySet = new HashSet<>();
      for (AuthTokenRes tokenRes : tokenStore.getUserAll(res.getUserKey())) {
        if (res.getDeviceType().equals(tokenRes.getDeviceType())) {
          keySet.add(tokenRes.getTokenKey());
        }
      }
      if (CollUtil.isNotEmpty(keySet)) {
        tokenStore.removeToken(res.getUserKey(), keySet);
      }
    }
    // 设置登录信息 缓存token
    setThreadLocal(res);
    clearOverdueUser(res.getUserKey());
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
      String str = Base62.decodeStr(token);
      List<String> split = StrUtil.split(str, "&&");
      String data = split.get(0);
      String signHex = split.get(1);
      // 校验签名
      if (!sign.verify(
          StrUtil.bytes(data, CharsetUtil.CHARSET_UTF_8), HexUtil.decodeHex(signHex))) {
        return false;
      }
      List<String> list = StrUtil.split(data, "@");
      AuthTokenRes authTokenRes = tokenStore.get(list.get(0), list.get(1));
      if (authTokenRes == null) {
        return false;
      } else {
        // 判断是否过期
        if (authTokenRes.getTimeout()
            <= System.currentTimeMillis() - authTokenRes.getLastAccessTime()) {
          tokenStore.removeToken(authTokenRes.getUserKey(), authTokenRes.getTokenKey());
          return false;
        }
        if (config.isOverdueReset()) {
          authTokenRes.setLastAccessTime(System.currentTimeMillis());
          tokenStore.put(authTokenRes.getUserKey(), authTokenRes.getTokenKey(), authTokenRes);
        }
        setThreadLocal(authTokenRes);
        return true;
      }
    } catch (Exception e) {
      return false;
    }
  }

  public static void clearOverdueUser(String userKey) {
    Collection<AuthTokenRes> userAll = tokenStore.getUserAll(userKey);
    if (CollUtil.isNotEmpty(userAll)) {
      checkOverdue(userKey, userAll);
    } else {
      if (userAll != null) {
        tokenStore.removeUser(userKey);
      }
    }
  }

  /**
   * 清理过期token
   *
   * @author 毛家兴
   * @since 2022/10/24 10:10
   */
  public static void clearOverdueToken() {
    Collection<String> allUserKey = tokenStore.getAllUserKey();
    if (CollUtil.isNotEmpty(allUserKey)) {
      for (String userKey : allUserKey) {
        Collection<AuthTokenRes> userAll = tokenStore.getUserAll(userKey);
        if (CollUtil.isNotEmpty(userAll)) {
          checkOverdue(userKey, userAll);
        }
      }
    }
  }

  private static void checkOverdue(String userKey, Collection<AuthTokenRes> userAll) {
    int size = userAll.size();
    int count = 0;
    for (AuthTokenRes res : userAll) {
      if (res.getTimeout() <= System.currentTimeMillis() - res.getLastAccessTime()) {
        count++;
        tokenStore.removeToken(res.getUserKey(), res.getTokenKey());
      }
    }
    if (size == count) {
      tokenStore.removeUser(userKey);
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
    String data = userKey + "@" + tokenKey + "@" + randomString;
    return Base62.encode(data + "&&" + sign.signHex(data));
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