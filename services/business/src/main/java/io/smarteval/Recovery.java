package io.smarteval;

import static io.smarteval.Api.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class Recovery {
    private final Database db;
    private final Identity identity;
    private final ObjectProvider<JavaMailSender> mailer;
    private final String sender;
    Recovery(Database db,Identity identity,ObjectProvider<JavaMailSender> mailer,@Value("${SMARTEVAL_SMTP_FROM:}") String sender){
        this.db=db;this.identity=identity;this.mailer=mailer;this.sender=sender;
    }
    record Delivery(UUID user,String address){}

    Object address(UUID user){
        String current=db.one("SELECT recovery_address FROM sys_user WHERE id=? AND recovery_verified_at IS NOT NULL",(r,i)->r.getString(1),user);
        return current==null?Map.of("verified",false):Map.of("verified",true,"address",current);
    }

    @Transactional
    public void bind(UUID user,String address,String password){
        var mail=mailer.getIfAvailable();
        if(mail==null||sender.isBlank())throw error(503,"MAIL_UNAVAILABLE","找回密码邮件服务暂不可用");
        try{
            var parsed=new jakarta.mail.internet.InternetAddress(address,true);
            parsed.validate();
            if(!address.equals(parsed.getAddress())||parsed.getPersonal()!=null||parsed.isGroup()||address.indexOf('@')<=0)
                throw new jakarta.mail.internet.AddressException("Expected an email address");
        }catch(jakarta.mail.internet.AddressException e){throw error(422,"INVALID_ADDRESS","请输入有效的邮箱地址");}
        identity.verifyPassword(user,password);
        var now=Instant.now();
        int recent=db.required("SELECT COUNT(*) FROM recovery_address_verification WHERE user_id=? AND created_at>=?",(r,i)->r.getInt(1),user,now.minusSeconds(3600));
        if(recent>=3)throw error(429,"MAIL_RATE_LIMITED","请一小时后再尝试");
        db.write("UPDATE recovery_address_verification SET used_at=? WHERE user_id=? AND used_at IS NULL",now,user);
        String token=Identity.random();
        db.write("INSERT INTO recovery_address_verification(id,user_id,address,token_hash,expires_at,created_at) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID(),user,address,sha(token),now.plusSeconds(900),now);
        deliverOrThrow(mail,address,"SmartEval 找回密码邮箱验证",token);
    }

    @Transactional
    public void confirmAddress(UUID user,String token){
        if(token.length()!=43)throw badToken();
        db.required("SELECT 1 FROM sys_user WHERE id=? AND status='ACTIVE' FOR UPDATE",(r,i)->1,user);
        var now=Instant.now();var hash=sha(token);
        String address=db.one("SELECT address FROM recovery_address_verification WHERE user_id=? AND token_hash=? AND expires_at>? AND used_at IS NULL",
                (r,i)->r.getString(1),user,hash,now);
        if(address==null)throw badToken();
        db.write("UPDATE recovery_address_verification SET used_at=? WHERE user_id=? AND token_hash=?",now,user,hash);
        db.write("UPDATE sys_user SET recovery_address=?,recovery_verified_at=?,row_version=row_version+1,updated_at=? WHERE id=?",address,now,now,user);
        db.write("UPDATE password_reset SET used_at=? WHERE user_id=? AND used_at IS NULL",now,user);
        db.audit(null,user,"RECOVERY_ADDRESS_VERIFIED","sys_user",user,Map.of());
    }

    @Transactional
    public void request(String username){
        var mail=mailer.getIfAvailable();
        if(mail==null||sender.isBlank())return;
        var user=db.one("""
                SELECT id,recovery_address FROM sys_user WHERE username_normalized=? AND status='ACTIVE'
                AND recovery_verified_at IS NOT NULL AND recovery_address IS NOT NULL FOR UPDATE
                """,(r,i)->new Delivery(Database.uuid(r,"id"),r.getString("recovery_address")),Identity.normalize(username));
        if(user==null)return;
        Instant now=Instant.now();
        Integer recent=db.one("SELECT COUNT(*) FROM password_reset WHERE user_id=? AND created_at>=?",(r,i)->r.getInt(1),user.user(),now.minusSeconds(3600));
        if(recent!=null&&recent>=3)return;
        String token=Identity.random();
        db.write("INSERT INTO password_reset(id,user_id,token_hash,expires_at,created_at) VALUES(?,?,?,?,?)",UUID.randomUUID(),user.user(),sha(token),now.plusSeconds(900),now);
        if (!deliverQuietly(mail,user.address(),"SmartEval 密码重置",token)) {
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    private SimpleMailMessage buildMessage(String address,String subject,String token){
        var message=new SimpleMailMessage();
        message.setFrom(sender);message.setTo(address);message.setSubject(subject);
        message.setText("请在 15 分钟内于 SmartEval 输入以下验证码。\n"+token);
        return message;
    }

    private void deliverOrThrow(JavaMailSender mail,String address,String subject,String token){
        try{mail.send(buildMessage(address,subject,token));}
        catch(org.springframework.mail.MailException e){
            org.slf4j.LoggerFactory.getLogger(Recovery.class).warn("Recovery email delivery failed ({})",e.getClass().getSimpleName());
            throw error(503,"MAIL_UNAVAILABLE","验证邮件发送失败，请稍后重试");
        }
    }

    private boolean deliverQuietly(JavaMailSender mail,String address,String subject,String token){
        try{mail.send(buildMessage(address,subject,token));return true;}
        catch(org.springframework.mail.MailException e){
            org.slf4j.LoggerFactory.getLogger(Recovery.class).warn("Password reset email delivery failed ({})",e.getClass().getSimpleName());
            return false;
        }
    }
    @Transactional
    public void reset(String token,String password){
        if(token.length()!=43)throw badToken();
        Instant now=Instant.now();byte[] hash=sha(token);
        UUID owner=db.one("SELECT user_id FROM password_reset WHERE token_hash=? AND expires_at>? AND used_at IS NULL",
                (r,i)->Database.uuid(r,"user_id"),hash,now);
        if(owner==null)throw badToken();
        db.required("SELECT 1 FROM sys_user WHERE id=? AND status='ACTIVE' FOR UPDATE",(r,i)->1,owner);
        if(db.write("UPDATE password_reset SET used_at=? WHERE user_id=? AND token_hash=? AND used_at IS NULL AND expires_at>?",
                now,owner,hash,now)!=1)throw badToken();
        db.write("UPDATE sys_user SET password_hash=?,row_version=row_version+1,updated_at=? WHERE id=?",identity.hashPassword(password),now,owner);
        db.write("UPDATE auth_session SET revoked_at=?,updated_at=? WHERE user_id=? AND revoked_at IS NULL",now,now,owner);
        db.write("UPDATE password_reset SET used_at=? WHERE user_id=? AND used_at IS NULL",now,owner);
        db.write("UPDATE recovery_address_verification SET used_at=? WHERE user_id=? AND used_at IS NULL",now,owner);
        db.audit(null,owner,"PASSWORD_RESET","sys_user",owner,Map.of());
    }
    private Api.Failure badToken(){return error(422,"RESET_TOKEN_INVALID","验证码无效或已过期");}
}

@RestController
@RequestMapping("/api/v2")
class RecoveryController{
    private final Recovery recovery;
    private final Identity identity;
    RecoveryController(Recovery recovery,Identity identity){this.recovery=recovery;this.identity=identity;}
    @GetMapping("/me/recovery-address")
    Object address(HttpServletRequest r){return recovery.address(identity.authenticate(r).user());}
    @PostMapping("/me/recovery-address")
    ResponseEntity<?> bind(@RequestBody JsonNode b,HttpServletRequest r){
        recovery.bind(identity.authenticate(r).user(),text(b,"address",254),text(b,"current_password",128));
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/me/recovery-address/confirm")
    ResponseEntity<?> confirmAddress(@RequestBody JsonNode b,HttpServletRequest r){
        recovery.confirmAddress(identity.authenticate(r).user(),text(b,"token",100));
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/auth/password-reset")
    ResponseEntity<?> request(@RequestBody JsonNode b){
        recovery.request(text(b,"username",120));return ResponseEntity.noContent().build();
    }
    @PostMapping("/auth/password-reset/confirm")
    ResponseEntity<?> reset(@RequestBody JsonNode b){
        recovery.reset(text(b,"token",100),text(b,"new_password",128));return ResponseEntity.noContent().build();
    }
}

