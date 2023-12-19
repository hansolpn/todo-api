package com.example.todo.userapi.api;

import com.example.todo.auth.TokenUserInfo;
import com.example.todo.exception.NoRegisteredArgumentsException;
import com.example.todo.userapi.dto.request.LoginRequestDTO;
import com.example.todo.userapi.dto.request.UserRequestSignUpDTO;
import com.example.todo.userapi.dto.response.LoginResponseDTO;
import com.example.todo.userapi.dto.response.UserSignUpResponseDTO;
import com.example.todo.userapi.entity.User;
import com.example.todo.userapi.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.FileCopyUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/auth")
@CrossOrigin
public class UserController {
    private final UserService userService;

    // 이메일 중복 확인 요청 처리
    // GET: /api/auth/check?email=zzzz@xxx.com
    @GetMapping("/check")
    public ResponseEntity<?> check(String email) {
        if (email.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body("이메일이 없습니다.");
        }

        boolean resultFlag = userService.isDuplicate(email);
        log.info("{} 증복?? - {}" ,email, resultFlag);

        return ResponseEntity.ok().body(resultFlag);
    }

    // 회원 가입 요청 처리
    // POST: /api/auth
    @PostMapping
    public ResponseEntity<?> signUp(
            @Validated @RequestPart("user") UserRequestSignUpDTO dto,
            @RequestPart(value = "profileImage", required = false) MultipartFile profileImg,
            BindingResult result) {
        log.info("/api/auth POST! dto - {}", dto);

        if (result.hasErrors()) {
            log.warn(result.toString());
            return ResponseEntity.badRequest()
                    .body(result.getFieldError());
        }

        try {
            String uploadedFliePath = null;

            if (profileImg != null) {
                log.info("attached file name: {}", profileImg.getOriginalFilename());
                // 전달받은 프로필 이미지를 먼저 지정딘 경로에 저장한 후 DB 저장을 위해 경로를 받아오자
                uploadedFliePath = userService.uploadProfileImage(profileImg);
            }

            UserSignUpResponseDTO responseDTO = userService.create(dto, uploadedFliePath);

            return ResponseEntity.ok()
                    .body(responseDTO);
            //return ResponseEntity.ok().body("");
        } catch (IOException e) {
            log.warn("파일 처리중 오류 발생!", e);

            return ResponseEntity.badRequest()
                    .body(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("이메일 중복!", e);

            return ResponseEntity.badRequest()
                    .body(e.getMessage());
        } catch (Exception e) {
            log.warn("기타 예외가 발생했습니다", e);

            return ResponseEntity.internalServerError().build();
        }

    }

    // 로그인 요청 처리
    @PostMapping("/signin")
    public ResponseEntity<?> signIn(
            @Validated @RequestBody LoginRequestDTO dto
    ) {
        try {
            LoginResponseDTO responseDTO = userService.authenticate(dto);

            return ResponseEntity.ok()
                    .body(responseDTO);
        } catch (Exception e) {
            log.error("/signin: error", e);
            return ResponseEntity.badRequest()
                    .body(e.getMessage());
        }

    }

    // 일반 회원을 프리미엄 회원으로 승격하는 요청 처리
    @PutMapping("/promote")
    // 권한 검사 (해당 권한이 아니라면 인가처리 거부 -> 403 코드 리턴)
    // 메서드 호출 전에 권한 검사 -> 요청 당시 토큰에 있는 user 정보가 ROLE_COMMON이라는 권한을 가지고 있는지 검사.
    @PreAuthorize("hasRole('ROLE_COMMON')")
    public ResponseEntity<?> promote(
            @AuthenticationPrincipal TokenUserInfo userInfo
            ) {
        log.info("/api/auth/promote PUT");

        try {
            LoginResponseDTO responseDTO = userService.promoteToPremium(userInfo);
            return ResponseEntity.ok()
                    .body(responseDTO);
        } catch (NoRegisteredArgumentsException | IllegalArgumentException e) {
            // 예상 가능한 예외 (직접 생성하는 예외 처리
            log.warn(e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(e.getMessage());

        } catch (Exception e) {
            // 예상하지 못한 예외 처리
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(e.getMessage());
        }
    }

    // 프로필 사진 이미지 데이터를 클라이언트에게 응답 처리
    @GetMapping("/load-profile")
    @CrossOrigin(exposedHeaders = "kakaoProfile")
    public ResponseEntity<?> loadFile(
            @AuthenticationPrincipal TokenUserInfo userInfo
    ) {
        log.info("/api/auth/load-profile - GET, user: {}", userInfo.getEmail());

        try {
            // 클라이언트가 요청한 프로필 사진을 응답해야 함
            // 1. 프로필 사진의 경로부터 얻어야 한다!
            String filePath = userService.findProfilePath(userInfo.getUserId());

            // 카카오 프로필 사진은 http로 시작되므로 http로 시작하면 따로 처리
            if (filePath != null && filePath.startsWith("http")) {
                return ResponseEntity.ok()
                        //.header("Access-Control-Expose-Headers", "kakao")
                        .header("kakaoProfile", "true")
                        .body(filePath);
            }

            // 2. 얻어낸 파일 경로를 통해 실제 파일 데이터를 로드하기
            File profileFile = new File(filePath);

            // 모든 사용자가 프로필 사진을 가지는 것은 아니다 -> 표시가 없는 사람들은 경로가 존재하지 않을 것이다
            // 만약 존재하지 않는 경로라면 클라이언트로 404 status를 리턴
            if (!profileFile.exists()) {
                return ResponseEntity.notFound().build();
            }

            // 해당 경로에 저장된 파일을 바이트 배열로 직렬화 해서 리턴
            byte[] fileData = FileCopyUtils.copyToByteArray(profileFile);
            
            // 3. 응답 헤더에 컨텐츠 타입을 설정
            HttpHeaders headers = new HttpHeaders();
            MediaType contentType = findExtensionAndGetMediaType(filePath);

            if (contentType == null) {
                return ResponseEntity.internalServerError()
                        .body("발견된 파일은 이미지 파일이 아닙니다.");
            }
            headers.setContentType(contentType);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(fileData);

        } catch (IOException e) {
            log.warn(e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body("파일을 찾을 수 없습니다.");
        }
    
    }

    private MediaType findExtensionAndGetMediaType(String filePath) {
        // 파일 경로에서 확장자 추출하기
        // c:/todo_upload/abc12....._abc.jpg
        String ext = filePath.substring(filePath.lastIndexOf(".") + 1);
        
        // 추출한 확장자를 바탕으로 MediaType을 설정 -> Header에 들어갈 content-type이 됨
        switch (ext.toUpperCase()) {
            case "JPG": case "JPEG":
                return MediaType.IMAGE_JPEG;
            case "PNG":
                return MediaType.IMAGE_PNG;
            case "GIF":
                return MediaType.IMAGE_GIF;
            default:
                return null;

        }
    }

    @GetMapping("/kakaoLogin")
    public ResponseEntity<?> kakaoLogin(String code) {
        log.info("/api/auth/kakaoLogin - GET! - code: {}", code);

        try {
            LoginResponseDTO responseDTO = userService.kakaoService(code);
            return ResponseEntity.ok().body(responseDTO);
        } catch (Exception e) {
            log.warn(e.getMessage(), e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }

    }

    @GetMapping("/logout")
    public ResponseEntity<?> logout(
            @AuthenticationPrincipal TokenUserInfo userInfo
    ) {
        log.info("/api/auth/logout - GET! - user: {}", userInfo.getEmail());

        String result = userService.logout(userInfo);

        return ResponseEntity.ok().body(result);
    }

    // s3에서 불러온 프로필 사진 처리
    @GetMapping("/load-s3")
    public ResponseEntity<?> loads3(
            @AuthenticationPrincipal TokenUserInfo userInfo
    ) {
        log.info("/api/auth/load-s3 - GET - user: {}", userInfo);

        try {
            String profilePath = userService.findProfilePath(userInfo.getUserId());
            return ResponseEntity.ok().body(profilePath);
        }
        catch (Exception e) {
            log.warn("/load-s3: {}\n{}", e.getMessage(), e.getStackTrace()[0]);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

}
