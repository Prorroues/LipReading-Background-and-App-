package com.alibaba.nls.token;

import java.io.IOException;

import com.alibaba.nls.client.AccessToken;

/**
 * @author siwei
 * @date 2019-10-30
 */
public class CreateToken {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("CreateTokenDemo need params: <accessKeyId> <accessKeySecret>");
            System.exit(-1);
        }

        String accessKeyId = args[0];
        String accessKeySecret = args[1];
        System.out.println("accessKeyId="+accessKeyId+"; accessKeySecret="+accessKeySecret);
        AccessToken accessToken = new AccessToken(accessKeyId, accessKeySecret);
        try {
            accessToken.apply();
            System.out.println("Token: " + accessToken.getToken() + ", expire time: " + accessToken.getExpireTime());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}