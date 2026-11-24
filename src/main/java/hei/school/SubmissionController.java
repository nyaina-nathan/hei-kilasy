package hei.school;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SubmissionController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JavaMailSender mailSender;

    private static final BigDecimal PENALTY_PER_DAY = new BigDecimal("0.10");
    private static final BigDecimal MAX_PENALTY = new BigDecimal("0.50");
    private static final BigDecimal MAX_GRADE = new BigDecimal("20");
    private static final String PENALTY_MARKER = "Pénalité de retard :";

    /**
     * The deadline is a date, so it is considered to be at 00:00 on that day. Any submission
     * after that instant is at least one day late, even if it happens on the deadline day.
     */
    private static long lateDays(LocalDate deadline, LocalDateTime submittedAt) {
        LocalDateTime deadlineStart = deadline.atStartOfDay();
        if (!submittedAt.isAfter(deadlineStart)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(deadline, submittedAt.toLocalDate()) + 1;
    }

    private static BigDecimal penaltyRate(long daysLate) {
        return PENALTY_PER_DAY.multiply(BigDecimal.valueOf(daysLate)).min(MAX_PENALTY);
    }

    private static int penaltyPercent(long daysLate) {
        return penaltyRate(daysLate).multiply(BigDecimal.valueOf(100)).intValue();
    }

    private static BigDecimal gradeOutOf20(BigDecimal points, BigDecimal maxPoints) {
        return points.multiply(MAX_GRADE).divide(maxPoints, 1, RoundingMode.HALF_UP);
    }

    /**
     * Keeps the teacher's feedback and appends the automatically generated penalty note.
     * Any previous penalty note is removed first so re-grading does not duplicate it.
     */
    private static String buildFeedback(String feedback, long daysLate, int penaltyPercent, BigDecimal initialGrade) {
        String base = feedback == null ? "" : feedback.strip();
        int marker = base.indexOf("\n\n" + PENALTY_MARKER);
        if (marker < 0 && base.startsWith(PENALTY_MARKER)) {
            marker = 0;
        }
        if (marker >= 0) {
            base = base.substring(0, marker).stripTrailing();
        }
        if (daysLate == 0) {
            return marker >= 0 ? base : feedback;
        }
        String note = PENALTY_MARKER + " " + penaltyPercent + " % ("
                + daysLate + (daysLate == 1 ? " jour" : " jours") + " de retard).\n"
                + "Note avant pénalité : " + initialGrade + "/20.";
        return base.isEmpty() ? note : base + "\n\n" + note;
    }

    @PostMapping("/exercises/{id}/submissions")
    public ResponseEntity<?> submit(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (body.get("studentId") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "studentId est obligatoire"));
        }
        long studentId = ((Number) body.get("studentId")).longValue();
        String content = (String) body.get("content");
        String repoUrl = (String) body.get("repoUrl");
        if ((content == null || content.isBlank()) && (repoUrl == null || repoUrl.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Il faut du code ou un lien vers un dépôt GitHub"));
        }
        if (repoUrl != null && !repoUrl.isBlank() && !repoUrl.startsWith("https://github.com/")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Le lien doit pointer vers GitHub"));
        }

        List<Map<String, Object>> exercises = jdbcTemplate.queryForList("SELECT * FROM exercise WHERE id = ?", id);
        if (exercises.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Exercice introuvable"));
        }
        List<Map<String, Object>> students = jdbcTemplate.queryForList("SELECT * FROM student WHERE id = ?", studentId);
        if (students.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Étudiant introuvable"));
        }
        Integer already = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM submission WHERE exercise_id = ? AND student_id = ?", Integer.class, id, studentId);
        if (already != null && already > 0) {
            return ResponseEntity.status(409).body(Map.of("error", "Cet étudiant a déjà soumis cet exercice"));
        }

        LocalDate deadline = ((java.sql.Date) exercises.get(0).get("deadline")).toLocalDate();
        LocalDateTime now = LocalDateTime.now();
        long daysLate = lateDays(deadline, now);
        boolean late = daysLate > 0;

        jdbcTemplate.update(
                "INSERT INTO submission (exercise_id, student_id, content, repo_url, submitted_at) VALUES (?, ?, ?, ?, ?)",
                id, studentId, content, repoUrl, Timestamp.valueOf(now));
        Long submissionId = jdbcTemplate.queryForObject("SELECT max(id) FROM submission", Long.class);

        Map<String, Object> result = new HashMap<>();
        result.put("id", submissionId);
        result.put("submittedAt", now);
        result.put("late", late);
        result.put("lateDays", daysLate);
        result.put("penaltyPercent", penaltyPercent(daysLate));
        result.put("message", late ? "Soumission enregistrée, mais en retard" : "Soumission enregistrée");
        return ResponseEntity.status(201).body(result);
    }

    @GetMapping("/submissions/{id}")
    public ResponseEntity<?> get(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.*, e.title, e.deadline, e.max_points FROM submission s JOIN exercise e ON e.id = s.exercise_id WHERE s.id = ?",
                id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Soumission introuvable"));
        }
        Map<String, Object> row = rows.get(0);
        LocalDate deadline = ((java.sql.Date) row.get("deadline")).toLocalDate();
        LocalDateTime submittedAt = ((Timestamp) row.get("submitted_at")).toLocalDateTime();

        long daysLate = lateDays(deadline, submittedAt);
        Map<String, Object> result = new HashMap<>();
        result.put("id", row.get("id"));
        result.put("exercise", row.get("title"));
        result.put("studentId", row.get("student_id"));
        result.put("content", row.get("content"));
        result.put("repoUrl", row.get("repo_url"));
        result.put("submittedAt", submittedAt);
        result.put("late", daysLate > 0);
        result.put("lateDays", daysLate);
        result.put("penaltyPercent", penaltyPercent(daysLate));
        if (row.get("raw_points") != null) {
            BigDecimal raw = (BigDecimal) row.get("raw_points");
            BigDecimal max = (BigDecimal) row.get("max_points");
            result.put("rawPoints", raw.doubleValue());
            result.put("grade", gradeOutOf20(raw, max).doubleValue());
            result.put("feedback", row.get("feedback"));
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping("/submissions/{id}/grade")
    public ResponseEntity<?> grade(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (body.get("points") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "points est obligatoire"));
        }
        double points = ((Number) body.get("points")).doubleValue();
        String feedback = (String) body.get("feedback");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.*, e.title, e.deadline, e.max_points, st.full_name, st.email, st.phone "
                        + "FROM submission s JOIN exercise e ON e.id = s.exercise_id JOIN student st ON st.id = s.student_id "
                        + "WHERE s.id = ?",
                id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Soumission introuvable"));
        }
        Map<String, Object> row = rows.get(0);
        BigDecimal maxPoints = (BigDecimal) row.get("max_points");
        double max = maxPoints.doubleValue();
        if (points < 0 || points > max) {
            return ResponseEntity.badRequest().body(Map.of("error", "Les points doivent être entre 0 et " + max));
        }

        LocalDate deadline = ((java.sql.Date) row.get("deadline")).toLocalDate();
        LocalDateTime submittedAt = ((Timestamp) row.get("submitted_at")).toLocalDateTime();
        long daysLate = lateDays(deadline, submittedAt);
        int penaltyPct = penaltyPercent(daysLate);
        boolean late = daysLate > 0;

        BigDecimal initialPoints = BigDecimal.valueOf(points);
        BigDecimal initialGrade = gradeOutOf20(initialPoints, maxPoints);
        BigDecimal penalizedPoints = initialPoints
                .multiply(BigDecimal.ONE.subtract(penaltyRate(daysLate)))
                .setScale(2, RoundingMode.HALF_UP);
        // grade out of 20, based on the penalized points
        BigDecimal grade = gradeOutOf20(penalizedPoints, maxPoints);
        String storedFeedback = buildFeedback(feedback, daysLate, penaltyPct, initialGrade);

        jdbcTemplate.update("UPDATE submission SET raw_points = ?, feedback = ?, graded_at = now() WHERE id = ?",
                penalizedPoints, storedFeedback, id);

        // notify the student by email
        String fullName = (String) row.get("full_name");
        String firstName = fullName.split(" ")[0];
        String title = (String) row.get("title");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("notes@kilasy.test");
        message.setTo((String) row.get("email"));
        message.setSubject("Kilasy : nouvelle note pour " + title);
        String text = "Bonjour " + firstName + ",\n\n"
                + "Ta note pour « " + title + " » est disponible : " + grade + "/20.\n";
        if (feedback != null && !feedback.isBlank()) {
            text += "\nCommentaire de l'enseignant : " + feedback + "\n";
        }
        text += "\nL'équipe Kilasy";
        message.setText(text);
        mailSender.send(message);
        System.out.println("Email de note envoyé à " + row.get("email"));

        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("rawPoints", penalizedPoints.doubleValue());
        result.put("maxPoints", max);
        result.put("grade", grade.doubleValue());
        result.put("late", late);
        result.put("lateDays", daysLate);
        result.put("penaltyPercent", penaltyPct);
        result.put("feedback", storedFeedback);
        return ResponseEntity.ok(result);
    }
}
