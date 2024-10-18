package de.kaliburg.morefair.security;

import de.kaliburg.morefair.FairConfig;
import de.kaliburg.morefair.account.services.AccountService;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.tomcat.util.http.SameSiteCookies;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
//@EnableRedisHttpSession(redisNamespace = "session:test")
public class SecurityConfig {

  private final AccountService accountService;
  private final PasswordEncoder passwordEncoder;
  private final RedisTokenRepositoryImpl redisTokenRepository;
  private final CookieSameSiteFilter cookieSameSiteFilter;
  private final FairConfig config;

  @Value("${spring.profiles.active}")
  private String activeProfile;

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
    http.csrf(csrf -> csrf
        .csrfTokenRepository(new CustomCookieCsrfTokenRepository())
        .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()::handle)
    );
    http.sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
    );
    http.securityContext(securityContext -> securityContext
        .requireExplicitSave(false)
    );
    http.addFilter(usernamePasswordAuthenticationFilter());
    http.authorizeHttpRequests((requests) -> requests
        .requestMatchers(
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/login"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/auth/**"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/auth/**"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/_content/**"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/stats/**"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/socket/fair")
        ).permitAll()

        .requestMatchers(
            AntPathRequestMatcher.antMatcher(HttpMethod.OPTIONS, "/api/**"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/**"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/**"),
            AntPathRequestMatcher.antMatcher(HttpMethod.PUT, "/api/**"),
            AntPathRequestMatcher.antMatcher(HttpMethod.PATCH, "/api/**"),
            AntPathRequestMatcher.antMatcher(HttpMethod.DELETE, "/api/**")
        ).authenticated()

        .anyRequest()
        .permitAll()
    );
    http.rememberMe().rememberMeServices(rememberMeServices());
    http.logout(logout -> logout
        .logoutUrl("/api/auth/logout")
        .invalidateHttpSession(true)
        .logoutSuccessHandler(logoutSuccessHandler())
        .deleteCookies("remember-me")
    );
    http.headers().frameOptions().disable()
        .contentSecurityPolicy("frame-ancestors 'self' https://galaxy.click");
    http.addFilterAfter(cookieSameSiteFilter, RememberMeAuthenticationFilter.class);
    http.exceptionHandling()
        .authenticationEntryPoint(authenticationEntryPoint());

    return http.build();
  }

  @Bean
  public UsernamePasswordAuthenticationFilter usernamePasswordAuthenticationFilter() {
    UsernamePasswordAuthenticationFilter authLoginFilter =
        new UsernamePasswordAuthenticationFilter(authenticationManager());
    authLoginFilter.setRequiresAuthenticationRequestMatcher(
        new AntPathRequestMatcher("/api/auth/login", "POST"));
    authLoginFilter.setRememberMeServices(rememberMeServices());
    authLoginFilter.setContinueChainBeforeSuccessfulAuthentication(true);
    authLoginFilter.setAuthenticationSuccessHandler(authenticationSuccessHandler());

    return authLoginFilter;
  }

  @Bean
  public AuthenticationManager authenticationManager() {
    DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
    authProvider.setUserDetailsService(accountService);
    authProvider.setPasswordEncoder(passwordEncoder);
    return new ProviderManager(authProvider);
  }

  @Bean
  public RememberMeServices rememberMeServices() {
    CustomPersistentTokenBasedRememberMeServices rememberMeService =
        new CustomPersistentTokenBasedRememberMeServices(config.getSecrets().getRememberMe(),
            accountService, redisTokenRepository);
    rememberMeService.setTokenValiditySeconds(86400 * RedisTokenRepositoryImpl.TOKEN_VALID_DAYS);
    rememberMeService.setUseSecureCookie(true);
    rememberMeService.setAlwaysRemember(false);
    return rememberMeService;
  }

  @Bean
  public AuthenticationSuccessHandler authenticationSuccessHandler() {
    return new CustomAuthenticationSuccessHandler();
  }

  @Bean
  public LogoutSuccessHandler logoutSuccessHandler() {
    return new HttpStatusReturningLogoutSuccessHandler(HttpStatus.OK);
  }

  @Bean
  public CookieSerializer cookieSerializer() {
    DefaultCookieSerializer serializer = new CustomCookieSerializer();
    serializer.setSameSite(SameSiteCookies.STRICT.getValue());
    serializer.setUseSecureCookie(true);
    return serializer;
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(
        Arrays.asList("http://localhost:3000", "http://localhost:8080", "https://fair.kaliburg.de",
            "https://fairtest.kaliburg.de", "https://galaxy.click"));
    configuration.setAllowedMethods(
        Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  @Bean
  AuthenticationEntryPoint authenticationEntryPoint() {
    return new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
  }

}
