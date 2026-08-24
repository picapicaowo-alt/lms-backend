package com.coursistant.lms.module.quiz.service;

import com.coursistant.lms.module.quiz.dto.authoring.CreateQuizRequest;
import com.coursistant.lms.module.quiz.dto.authoring.PatchQuizRequest;
import com.coursistant.lms.module.quiz.dto.authoring.QuizResponse;
import com.coursistant.lms.module.quiz.entity.Quiz;
import com.coursistant.lms.module.quiz.repository.QuizMapper;
import com.coursistant.lms.module.quiz.repository.QuizQuestionMapper;
import com.coursistant.lms.module.tenant.service.TenantTimezoneService;
import com.coursistant.lms.shared.api.ApiException;
import com.coursistant.lms.shared.api.ErrorType;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
public class QuizAuthoringService {

    @Resource
    private QuizMapper quizMapper;
    @Resource
    private QuizQuestionMapper quizQuestionMapper;
    @Resource
    private QuizAccessService quizAccessService;
    @Resource
    private QuizTimeSupport quizTimeSupport;
    @Resource
    private TenantTimezoneService tenantTimezoneService;
    @Resource
    private QuizAuditService quizAuditService;
    @Lazy
    @Resource
    private QuizQuestionService quizQuestionService;

    public List<QuizResponse> list(HttpServletRequest request, Integer courseId, Integer userId) {
        quizAccessService.requireCourse(courseId);
        boolean staff = quizAccessService.isStaffViewer(request, courseId, userId);
        List<Quiz> quizzes = staff
                ? quizMapper.selectByCourseId(courseId)
                : quizMapper.selectByCourseIdAndState(courseId, QuizConstants.STATE_PUBLISHED);
        ZoneId zone = tenantTimezoneService.requireZoneForCourse(courseId);
        List<QuizResponse> out = new ArrayList<>();
        for (Quiz q : quizzes) {
            out.add(toResponse(q, zone));
        }
        return out;
    }

    public QuizResponse detail(HttpServletRequest request, Integer courseId, Integer quizId,
                               Integer userId) {
        Quiz quiz = quizAccessService.requireQuizReadable(request, courseId, quizId, userId);
        return toResponse(quiz, tenantTimezoneService.requireZoneForCourse(courseId));
    }

    @Transactional
    public QuizResponse create(Integer courseId, Integer userId, CreateQuizRequest body) {
        quizAccessService.requireNewActivityEnabled();
        quizAccessService.requireCourseWritable(courseId, userId);
        validateCreate(body);
        ZoneId zone = tenantTimezoneService.requireZoneForCourse(courseId);
        var now = quizTimeSupport.nowUtc();
        Quiz quiz = new Quiz();
        quiz.setCourseId(courseId);
        quiz.setTitle(trim(body.getTitle(), 200));
        quiz.setInstructions(trim(body.getInstructions(), 10000));
        quiz.setOpensAt(quizTimeSupport.toUtc(body.getOpensAt(), zone));
        quiz.setClosesAt(quizTimeSupport.toUtc(body.getClosesAt(), zone));
        quiz.setTimeLimitSeconds(body.getTimeLimitSeconds());
        quiz.setAttemptsAllowed(body.getAttemptsAllowed() == null ? 1 : body.getAttemptsAllowed());
        quiz.setResultVisibility(body.getResultVisibility() == null
                ? QuizConstants.VISIBILITY_AFTER_RELEASE : body.getResultVisibility());
        quiz.setState(QuizConstants.STATE_DRAFT);
        quiz.setVersion(1);
        quiz.setCreatedBy(userId);
        quiz.setCreatedAt(now);
        quiz.setUpdatedAt(now);
        quizMapper.insert(quiz);
        quizAuditService.log(courseId, quiz.getId(), null, userId, "QUIZ_CREATED", null, null);
        return toResponse(quiz, zone);
    }

    @Transactional
    public QuizResponse patch(Integer courseId, Integer quizId, Integer userId, PatchQuizRequest body) {
        Quiz quiz = quizAccessService.requireQuizConfigurable(courseId, quizId, userId);
        if (body.getExpectedVersion() != null && !body.getExpectedVersion().equals(quiz.getVersion())) {
            throw new ApiException(ErrorType.QUIZ_VERSION_CONFLICT);
        }
        boolean hasAttempts = quizMapper.countAttemptsByQuizId(quizId) > 0;
        ZoneId zone = tenantTimezoneService.requireZoneForCourse(courseId);
        if (body.getTitle() != null) {
            quiz.setTitle(trim(body.getTitle(), 200));
        }
        if (body.getInstructions() != null) {
            quiz.setInstructions(trim(body.getInstructions(), 10000));
        }
        if (body.getOpensAt() != null) {
            var newOpens = quizTimeSupport.toUtc(body.getOpensAt(), zone);
            if (hasAttempts && newOpens.isAfter(quiz.getOpensAt())) {
                throw new ApiException(ErrorType.BAD_REQUEST, "Cannot delay opensAt after attempts exist");
            }
            quiz.setOpensAt(newOpens);
        }
        if (body.getClosesAt() != null) {
            var newCloses = quizTimeSupport.toUtc(body.getClosesAt(), zone);
            if (hasAttempts && newCloses.isBefore(quiz.getClosesAt())) {
                throw new ApiException(ErrorType.BAD_REQUEST, "Cannot shorten closesAt after attempts exist");
            }
            quiz.setClosesAt(newCloses);
        }
        if (body.getTimeLimitSeconds() != null) {
            if (hasAttempts && body.getTimeLimitSeconds() < nullSafe(quiz.getTimeLimitSeconds())) {
                throw new ApiException(ErrorType.BAD_REQUEST, "Cannot reduce time limit after attempts exist");
            }
            quiz.setTimeLimitSeconds(body.getTimeLimitSeconds());
        }
        if (body.getAttemptsAllowed() != null) {
            quiz.setAttemptsAllowed(body.getAttemptsAllowed());
        }
        if (body.getResultVisibility() != null) {
            quiz.setResultVisibility(body.getResultVisibility());
        }
        if (quiz.getOpensAt() == null || quiz.getClosesAt() == null || !quiz.getOpensAt().isBefore(quiz.getClosesAt())) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Invalid quiz window");
        }
        quiz.setVersion(quiz.getVersion() + 1);
        quiz.setUpdatedAt(quizTimeSupport.nowUtc());
        if (quizMapper.updateById(quiz) == 0) {
            throw new ApiException(ErrorType.QUIZ_VERSION_CONFLICT);
        }
        quizAuditService.log(courseId, quizId, null, userId, "QUIZ_UPDATED", null, null);
        return toResponse(quizMapper.selectById(quizId), zone);
    }

    @Transactional
    public QuizResponse publish(Integer courseId, Integer quizId, Integer userId) {
        quizAccessService.requireNewActivityEnabled();
        Quiz quiz = quizAccessService.requireQuizConfigurable(courseId, quizId, userId);
        validatePublishable(quiz);
        quizMapper.updateState(quizId, QuizConstants.STATE_PUBLISHED);
        quizAuditService.log(courseId, quizId, null, userId, "QUIZ_PUBLISHED", null, null);
        return toResponse(quizMapper.selectById(quizId), tenantTimezoneService.requireZoneForCourse(courseId));
    }

    @Transactional
    public QuizResponse unpublish(Integer courseId, Integer quizId, Integer userId) {
        Quiz quiz = quizAccessService.requireQuizConfigurable(courseId, quizId, userId);
        if (quizMapper.countAttemptsByQuizId(quizId) > 0) {
            throw new ApiException(ErrorType.QUIZ_HAS_ATTEMPTS);
        }
        quizMapper.updateState(quizId, QuizConstants.STATE_DRAFT);
        quizAuditService.log(courseId, quizId, null, userId, "QUIZ_UNPUBLISHED", null, null);
        return toResponse(quizMapper.selectById(quizId), tenantTimezoneService.requireZoneForCourse(courseId));
    }

    @Transactional
    public void delete(Integer courseId, Integer quizId, Integer userId) {
        Quiz quiz = quizAccessService.requireQuizConfigurable(courseId, quizId, userId);
        if (quizMapper.countAttemptsByQuizId(quizId) > 0) {
            throw new ApiException(ErrorType.QUIZ_HAS_ATTEMPTS);
        }
        quizMapper.deleteById(quizId);
        quizAuditService.log(courseId, quizId, null, userId, "QUIZ_DELETED", null, null);
    }

    private void validateCreate(CreateQuizRequest body) {
        if (body == null || body.getTitle() == null || body.getTitle().isBlank()) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Title is required");
        }
        if (body.getOpensAt() == null || body.getClosesAt() == null) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Opens and closes times are required");
        }
    }

    private void validatePublishable(Quiz quiz) {
        if (quiz.getTitle() == null || quiz.getTitle().isBlank()) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Title required");
        }
        if (!quiz.getOpensAt().isBefore(quiz.getClosesAt())) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Invalid window");
        }
        if (quiz.getAttemptsAllowed() == null || quiz.getAttemptsAllowed() < 1) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Attempts allowed must be >= 1");
        }
        if (quizQuestionMapper.countByQuizId(quiz.getId()) == 0) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Quiz must have at least one question");
        }
        // Full option/answer-key completeness is enforced in QuizQuestionService on create/patch;
        // re-check at publish via question service helper.
        quizQuestionService.assertPublishReady(quiz.getId());
    }

    private QuizResponse toResponse(Quiz quiz, ZoneId zone) {
        QuizResponse r = new QuizResponse();
        r.setId(quiz.getId());
        r.setCourseId(quiz.getCourseId());
        r.setTitle(quiz.getTitle());
        r.setInstructions(quiz.getInstructions());
        r.setOpensAtUtc(quizTimeSupport.toInstant(quiz.getOpensAt()));
        r.setOpensAtLocal(quizTimeSupport.toZone(quiz.getOpensAt(), zone));
        r.setClosesAtUtc(quizTimeSupport.toInstant(quiz.getClosesAt()));
        r.setClosesAtLocal(quizTimeSupport.toZone(quiz.getClosesAt(), zone));
        r.setTimezone(zone.getId());
        r.setTimeLimitSeconds(quiz.getTimeLimitSeconds());
        r.setAttemptsAllowed(quiz.getAttemptsAllowed());
        r.setResultVisibility(quiz.getResultVisibility());
        r.setState(quiz.getState());
        r.setWindowOpen(quizTimeSupport.isWindowOpen(quiz.getOpensAt(), quiz.getClosesAt()));
        r.setVersion(quiz.getVersion());
        r.setTotalPoints(quizQuestionMapper.sumPointsByQuizId(quiz.getId()));
        r.setQuestionCount(quizQuestionMapper.countByQuizId(quiz.getId()));
        r.setHasAttempts(quizMapper.countAttemptsByQuizId(quiz.getId()) > 0);
        r.setCreatedAt(quizTimeSupport.toInstant(quiz.getCreatedAt()));
        r.setUpdatedAt(quizTimeSupport.toInstant(quiz.getUpdatedAt()));
        return r;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static int nullSafe(Integer v) {
        return v == null ? 0 : v;
    }
}
