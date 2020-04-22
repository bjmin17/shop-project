package com.biz.sec.service;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.biz.sec.domain.AuthorityVO;
import com.biz.sec.domain.UserDetailsVO;
import com.biz.sec.persistence.AuthoritiesDao;
import com.biz.sec.persistence.UserDao;
import com.biz.sec.utils.PbeEncryptor;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class UserService {
	
	// @Autowired
	private final PasswordEncoder passwordEncoder;
	
	private final UserDao userDao;
	private final AuthoritiesDao authDao;
	
	private final MailSendService mailService;
	
	public UserService(PasswordEncoder passwordEncoder, 
			UserDao userDao, 
			AuthoritiesDao authDao,
			MailSendService mailService) {
		super();
		this.passwordEncoder = passwordEncoder;
		this.userDao = userDao;
		this.authDao = authDao;
		this.mailService = mailService;
		
		String create_user_table = 
				" CREATE TABLE IF NOT EXISTS tbl_users(" + 
				"	id bigint PRIMARY KEY AUTO_INCREMENT," + 
				"	user_name varchar(50) UNIQUE," + 
				"    user_pass varchar(125)," + 
				"    enabled boolean default true," +
				"    email varchar(50)," + 
				"    phone varchar(20)," + 
				"    address varchar(125)" + 
				" ) ";
		
		String create_auth_table =
				" CREATE TABLE IF NOT EXISTS authorities(" + 
				"	id bigint PRIMARY KEY AUTO_INCREMENT," + 
				"    username varchar(50)," + 
				"    authority varchar(50)" + 
				" ) ";
		
		userDao.create_table(create_user_table);
		userDao.create_table(create_auth_table);
	}


	/**
	 * @since 2020-04-09
	 * @author bjmin17
	 * 
	 * @param username
	 * @param password
	 * @return
	 * 
	 * 회원가입을 신청하면 비밀번호는 암호화하고
	 * 아이디와 비밀번호를 DB에 insert 수행
	 * 
	 * 2020-04-10 Map 구조의 VO 데이터를 UserVO로 변경
	 * 
	 */
	@Transactional
	public int insert(String username, String password) {
		
		// 회원가입 form에서 전달받은 password 값을 암호화시키는 과정
		String encPassword = passwordEncoder.encode(password);
		UserDetailsVO userVO = UserDetailsVO.builder()
							.username(username)
							.password(encPassword).build();
		
		
		int ret = userDao.insert(userVO);
		List<AuthorityVO> authList = new ArrayList();
		authList.add(AuthorityVO.builder()
				.username(userVO.getUsername())
				.authority("ROLE_USER").build());
		authList.add(AuthorityVO.builder()
				.username(userVO.getUsername())
				.authority("USER").build());
		
		authDao.insert(authList);
		
		return ret;
	}

	/**
	 * @since 2020-04-20
	 * @author bjmin17
	 * 
	 * 새로 작성된 회원가입에서 회원가입을 처리할 method
	 * 
	 * email 인증방식으로 회원가입을 처리할 것이므로
	 * userVO를 파라메터로 받아서 
	 * enabled를 false로 처리하고
	 * role 정보는 업데이트하지 않는 것으로 처리해 놓는다.
	 * 
	 * 이후 email 인증이 오면
	 * enabled와 role 정보를 설정하도록 한다.
	 * 
	 * @param userVO
	 * @return
	 */
	
	@Transactional(isolation = Isolation.READ_COMMITTED,
				rollbackFor = Exception.class)
	public int insert(UserDetailsVO userVO) {
//	public String insert(UserDetailsVO userVO) {

		// 회원정보에 저장할 준비가 되지만
		// 로그인을 했을 때 접근 금지가 된 사용자가 된다.
		userVO.setEnabled(false);
		userVO.setAuthorities(null);
		
		// 회원가입 form에서 전달받은 password 값을 암호화시키는 과정
		String encPassword = passwordEncoder.encode(userVO.getPassword());
		userVO.setPassword(encPassword);
		
//		boolean bRet = mailService.join_send(userVO);
		String sRet;
		try {
			sRet = mailService.join_send(userVO);
//			return sRet;
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		int ret = userDao.insert(userVO);
		
		return ret;
	}
	

	public boolean isExistsUserName(String username) {

		UserDetailsVO userVO = userDao.findByUserName(username);
		// 이미 DB에 회원정보(username)이 저장되어 있다.
		if(userVO != null && userVO.getUsername().equalsIgnoreCase(username)) {
			return true;
		}
		
		return false;
		
	}


	public UserDetailsVO findById(long id) {
		UserDetailsVO userVO = userDao.findById(id);
		
		return userVO;
	}


	public boolean check_password(String password) {

		// context 뽑아오고, authentication 뽑아오고, principal 뽑아오기
		// 이 방법은 본인 것만 수정 가능, 관리자는 비밀번호를 변경 못한다.
		UserDetailsVO userVO = (UserDetailsVO) SecurityContextHolder
									.getContext()
									.getAuthentication()
									.getPrincipal();
		
		return passwordEncoder.matches(password,userVO.getPassword());
	}

	@Transactional
	public int update(UserDetailsVO userVO,String[] authList) {
		
		int ret = userDao.update(userVO);
		
		// DB update가 성공하면
		// 로그인된 session 정보를 update 수행
		if(ret > 0) {
			
//			ret = authDao.update(new ArrayList(Arrays.asList(authList));
			List<AuthorityVO> authCollection = new ArrayList();
			for(String auth : authList) {
				// auth가 빈칸인 경우가 생기지 않게 검사
				if(!auth.isEmpty()) {
					AuthorityVO authVO = AuthorityVO.builder()
							.username(userVO.getUsername())
							.authority(auth).build();
					authCollection.add(authVO);
				}
			}
			
			authDao.delete(userVO.getUsername());
			authDao.insert(authCollection);
		}
		
		return ret;
	}
	
	@Transactional
	public int update(UserDetailsVO userVO) {
		
		Authentication oldAuth = SecurityContextHolder.getContext().getAuthentication();
		
		UserDetailsVO oldUserVO = (UserDetailsVO) oldAuth.getPrincipal();
		
		oldUserVO.setEmail(userVO.getEmail());
		oldUserVO.setPhone(userVO.getPhone());
		oldUserVO.setAddress(userVO.getAddress());
		
		int ret = userDao.update(oldUserVO);
		// DB update가 성공하면
		// 로그인된 session 정보를 update 수행
		if(ret > 0) {

			// 새로운 session 정보를 만들때 oldUserVO로 세팅을 한다.
			Authentication newAuth = new UsernamePasswordAuthenticationToken(oldUserVO, // 변경된 사용자 정보 
					oldAuth.getCredentials(), 
					oldAuth.getAuthorities()// 변경된 ROLE 정보
					);
//			 get으로 뽑아낸것중에 credential만 new Auth로 바꾸고 context에 세팅해주기
			SecurityContextHolder.getContext().setAuthentication(newAuth);
		}
		
		return ret;
	}
	
	private Collection<GrantedAuthority> getAuthorities(String[] authList) {
		List<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();
		
		for(String auth : authList) {
			// auth가 빈칸이 아닐 경우만 추가를 시켜라
			if(!auth.isEmpty()) {
				SimpleGrantedAuthority sAuth = new SimpleGrantedAuthority(auth);
				authorities.add(sAuth);
				
			}
		}
		
		return authorities;
	}


	@Transactional
	public List<UserDetailsVO> selectAll() {
		return userDao.selectAll();
	}

	@Transactional
	public UserDetailsVO findByUserName(String username) {
		return userDao.findByUserName(username);
	}


	@Transactional
	public boolean emailok(String username, String email) {

		String strUserName = PbeEncryptor.getDecrypt(username);
		UserDetailsVO userVO = userDao.findByUserName(strUserName);
		
		String strEmail = PbeEncryptor.getDecrypt(email);
		if(strEmail.equalsIgnoreCase(userVO.getEmail())) {
			userVO.setEnabled(true);
			
			userDao.update(userVO);
			
			List<AuthorityVO> authList = new ArrayList();
			authList.add(AuthorityVO.builder()
					.username(userVO.getUsername())
					.authority("ROLE_USER").build());
			authList.add(AuthorityVO.builder()
					.username(userVO.getUsername())
					.authority("USER").build());
			
			authDao.insert(authList);
			return true;
		}
		return false;
	}


	/**
	 * @since 2020-04-21
	 * 회원 정보를 받아서 DB에 저장하고
	 * 회원 정보를 활성화 할 수 있도록 하기 위해
	 * 인증 정보를 생성한 후
	 * Controller로 Return
	 * @param userVO
	 * @return
	 */
	@Transactional
	public String insert_getToken(UserDetailsVO userVO) {
		// DB에 저장
		userVO.setEnabled(false);
		String encPassword = passwordEncoder.encode(userVO.getPassword());
		userVO.setPassword(encPassword);
		userDao.insert(userVO);
		
		
		// UUID는 asjdkldsj-asdj-sadjais 같이 - 문자열로 만들어져있다.
		String email_token = UUID.randomUUID().toString().split("-")[0].toUpperCase();
		
		// 암호화
		log.debug("EMAIL_TOKEN : " + email_token);
		String enc_email_token = PbeEncryptor.getEncrypt(email_token);
		
		// Email 보내기
		mailService.email_auth(userVO, email_token);
		return enc_email_token;
	}


	public boolean email_token_ok(String username, String secret_key, String secret_value) {
		
		boolean bKey = PbeEncryptor.getDecrypt(secret_key).equals(secret_value);
		if(bKey) {
			String strUserName = PbeEncryptor.getDecrypt(username);
			UserDetailsVO userVO = userDao.findByUserName(strUserName);
			
			userVO.setEnabled(true);
			userDao.update(userVO);
			authDao.delete(userVO.getUsername());
			List<AuthorityVO> authList = new ArrayList();
			authList.add(AuthorityVO.builder()
					.username(userVO.getUsername())
					.authority("ROLE_USER").build());
			authList.add(AuthorityVO.builder()
					.username(userVO.getUsername())
					.authority("USER").build());
			
			authDao.insert(authList);
		}
		
		return bKey;
	}

}
