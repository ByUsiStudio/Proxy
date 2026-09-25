package miao.byusi.proxy_client.service;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import miao.byusi.proxy_client.config.ConstConfig;

import java.io.EOFException;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UserService {
    /**
     * 登录
     *
     * @param username
     * @param password
     */
    public void login(String username, String password, final Handler handler) {
        OkHttpClient okHttpClient = new OkHttpClient();
        FormBody.Builder builder = new FormBody.Builder();
        //添加参数
        builder.addEncoded("username", username);
        builder.addEncoded("password", password);
        FormBody build = builder.build();
        final Request request = new Request.Builder()
                .url(ConstConfig.URL + "/user/login")
                .post(build)//默认就是GET请求，可以不写
                .build();
        Call call = okHttpClient.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Message message = new Message();
                message.what = -1;
                message.obj = "登陆失败";
                handler.sendMessage(message);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Message message = new Message();
                try {
                    String string = response.body().string();
                    Log.e("----", string);
                    JSONObject jsonObject = JSON.parseObject(string);
                    if (jsonObject.getIntValue("code") == 200) {
                        message.what = 1;
                        message.obj = jsonObject.getString("data");
                    } else {
                        message.what = -1;
                        message.obj = jsonObject.getString("msg");
                    }
                }catch (Throwable e){
                    message.what = -1;
                    message.obj = "登陆失败";
                }
                handler.sendMessage(message);
            }
        });
    }

    public void loadServer(final Handler handler) {
        OkHttpClient okHttpClient = new OkHttpClient();
        final Request request = new Request.Builder()
                .url(ConstConfig.URL + "/load/data")
                .build();
        Call call = okHttpClient.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Message message = new Message();
                message.what = -2;
                message.obj = "代理服务器获取失败";
                handler.sendMessage(message);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Message message = new Message();
                try {
                    String string = response.body().string();
                    Log.e("----", string);
                    JSONObject jsonObject = JSON.parseObject(string);
                    if (jsonObject.getIntValue("code") == 200) {
                        message.what = -2;
                        message.obj = jsonObject.getString("data");
                    } else {
                        message.what = -3;
                        message.obj = jsonObject.getString("msg");
                    }
                }catch (Throwable e){
                    message.what = -3;
                    message.obj = "代理服务器获取失败";
                }
                handler.sendMessage(message);
            }
        });
    }


    public void reg(String username, String password, final Handler handler) {
        OkHttpClient okHttpClient = new OkHttpClient();
        FormBody.Builder builder = new FormBody.Builder();
        //添加参数
        builder.addEncoded("username", username);
        builder.addEncoded("password", password);
        FormBody build = builder.build();
        final Request request = new Request.Builder()
                .url(ConstConfig.URL + "/user/reg")
                .post(build)//默认就是GET请求，可以不写
                .build();
        Call call = okHttpClient.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Message message = new Message();
                message.what = -1;
                message.obj = "注册失败";
                handler.sendMessage(message);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Message message = new Message();
                try {
                    String string = response.body().string();
                    Log.e("----", string);
                    JSONObject jsonObject = JSON.parseObject(string);
                    if (jsonObject.getIntValue("code") == 200) {
                        message.what = 1;
                        message.obj = jsonObject.getString("msg");
                    } else {
                        message.what = -1;
                        message.obj = jsonObject.getString("msg");
                    }
                }catch (Throwable e){
                    message.what = -1;
                    message.obj = "注册失败";
                }
                handler.sendMessage(message);
            }
        });
    }


    public void getVersion(final Handler handler) {
        OkHttpClient okHttpClient = new OkHttpClient();
        final Request request = new Request.Builder()
                .url(ConstConfig.URL + "/app/getVersion")
                .build();
        Call call = okHttpClient.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Message message = new Message();
                message.what = -1;
                message.obj = "检查更新失败";
                handler.sendMessage(message);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Message message = new Message();
                try {
                    String string = response.body().string();
                    JSONObject jsonObject = JSON.parseObject(string);
                    if (jsonObject.getIntValue("code") == 200) {
                        message.what = 1;
                        message.obj = jsonObject.getString("data");
                    } else {
                        message.what = -1;
                        message.obj = "检查更新失败";
                    }
                }catch (Throwable e){
                    message.what = -1;
                    message.obj = "检查更新失败";
                }
                handler.sendMessage(message);
            }
        });
    }


    /**
     * 查询流量统计
     *
     * 【安全修复】云端 /statistics/getMyInfo 现在要求携带账号凭据做归属校验
     * （避免只凭 username 就能读取他人流量记录），因此新增 password 参数。
     *
     * @param page     页码
     * @param username 当前登录账号
     * @param password 当前登录密码
     */
    public void getLog(final Handler handler,Integer page,String username,String password) {

        if (page==-1){
            Message message = new Message();
            message.what = -1;
            message.obj = "没有更多数据了";
            handler.sendMessage(message);
            return;
        }

        String safeUser = android.net.Uri.encode(username == null ? "" : username);
        String safePass = android.net.Uri.encode(password == null ? "" : password);
        OkHttpClient okHttpClient = new OkHttpClient();
        final Request request = new Request.Builder()
                .url(ConstConfig.URL + "/statistics/getMyInfo?page="+page+"&username="+safeUser+"&password="+safePass)
                .build();
        Call call = okHttpClient.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Message message = new Message();
                message.what = -1;
                message.obj = "没有更多数据了";
                handler.sendMessage(message);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Message message = new Message();
                try {
                    String string = response.body().string();
                    JSONObject jsonObject = JSON.parseObject(string);
                    if (jsonObject.getIntValue("code") == 200) {
                        message.what = 1;
                        message.obj = jsonObject.getString("data");
                    } else {
                        message.what = -1;
                        message.obj = "没有更多数据了";
                    }
                }catch (Throwable e){
                    message.what = -1;
                    message.obj = "没有更多数据了";
                }
                handler.sendMessage(message);
            }
        });
    }

}
