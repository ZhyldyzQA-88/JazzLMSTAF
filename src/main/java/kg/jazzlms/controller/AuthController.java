package kg.jazzlms.controller;

import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import kg.jazzlms.models.request.LoginRequest;
import kg.jazzlms.models.response.LoginResponse;
import kg.jazzlms.models.response.UserResponse;

import static io.restassured.RestAssured.given;
import static kg.jazzlms.constants.Endpoints.LOGIN;
import static kg.jazzlms.constants.Endpoints.ME;

/** /api/auth/** — логин публичный, поэтому без токена в конструкторе */
public class AuthController {

    private final String url;

    public AuthController(String url) {
        this.url = url;
    }

    public Response loginResponse(String username, String password) {
        return given()
                .baseUri(url)
                .contentType(ContentType.JSON)
                .filter(new RequestLoggingFilter())
                .filter(new ResponseLoggingFilter())
                .body(new LoginRequest(username, password))
                .post(LOGIN);
    }

    public LoginResponse login(String username, String password) {
        return loginResponse(username, password).then().statusCode(200).extract().as(LoginResponse.class);
    }

    public UserResponse me(String token) {
        return given()
                .baseUri(url)
                .header("Authorization", "Bearer " + token)
                .get(ME)
                .then().statusCode(200).extract().as(UserResponse.class);
    }
}
