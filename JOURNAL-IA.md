# Journal IA

Outil utilise: Opencode Desktop & CLI

## Changement: Penalite sur submission

> Mode: plan
> Model: DeepSeek V4.1 Flash

>Demande: 
>
> `Update the grading endpoint set inside @src/main/java/hei/school/SubmissionController.java by adding a new system of penalty based on the date the submission is sent compared to the deadline of the exercises, these are the rules:
>- As you can see we have the raw note and the note set with a max of 20 but the latter one is based on the first one so you will have to change the first one and the second one will follow accordingly
>- Submission sent on the exact day so at 00:00 do not receive penalty
>- The note will be given a  10% penalty after each day it was not submitted, for example if we have a note of 50 out of 100 then on the first day late ( after 00:00) we will remove 5 and then on the second day another 5 and so on and so on with a max of 50% of the grade so it cannot the note cannot be penalized by more than 50% and thus even a 10 days late will be penalized of 50%. 
>- As you can see the 10% penalty is computed from the initial grade and not from the new grade we got from the penalt`

Reponse:

Here’s the plan I’d apply, based only on `SubmissionController.java` and the existing schema.

## Penalty logic

- Count late days using calendar dates:
  - `daysLate = ChronoUnit.DAYS.between(deadline, submittedAt.toLocalDate())`
  - clamp to `0` if negative.
- A submission on the deadline date is `0` days late, even at `00:00`.
- Starting at `00:00` the next day, `daysLate = 1`.
- Penalty rate: `min(daysLate × 10%, 50%)`.
- Apply the rate to the teacher’s initial points, not to the already-penalized value:
  - `penalizedRaw = initialPoints × (1 − penaltyRate)`
  - round `penalizedRaw` to 2 decimals to match `NUMERIC(5,2)`.
- Final grade stays out of 20:
  - `grade = penalizedRaw / maxPoints × 20`, rounded to 1 decimal as today.
- `late = daysLate > 0`.

The 50% cap is reached on the 5th late day, so 5, 10, or 100 days late all give a 50% penalty.

## Example with an initial note of 50/100

| Sent | Days late | Penalty | New raw | Grade /20 |
|---|---:|---:|---:|---:|
| On deadline day | 0 | 0% | 50 | 10.0 |
| Next day | 1 | 10% | 45 | 9.0 |
| +2 days | 2 | 20% | 40 | 8.0 |
| +5 days | 5 | 50% | 25 | 5.0 |
| +10 days | 10 | 50% (capped) | 25 | 5.0 |

## Code changes in `SubmissionController.java`

1. Add `ChronoUnit` and `RoundingMode` imports.
2. Add small helpers/constants:
   - `PENALTY_PER_DAY = 0.10`
   - `MAX_PENALTY = 0.50`
   - `lateDays(deadline, submittedAt)`
   - `penaltyRate(daysLate)`
   - `outOf20(points, max)`
3. Update `grade(...)`:
   - Validate the initial points against `max_points` first, before any penalty.
   - Read `submitted_at` and `deadline` from the existing joined row.
   - Compute the penalty, then persist the penalized value into `submission.raw_points`.
   - Compute `grade` from the penalized raw value.
   - Add `lateDays`, `penaltyPercent`, and `initialPoints` to the response. `rawPoints` becomes the stored/penalized raw value.
   - Add a line to the email when a penalty applies, e.g.:
     `Pénalité de retard : 20 % (2 jours de retard).`
4. Update `get(...)`:
   - Reuse the same `outOf20` helper so its grade matches the grade endpoint.
   - Expose `lateDays` and `penaltyPercent` as informational fields.
5. No schema change. Because the penalized value is written to `raw_points`, `GradeReport` and `CsvExport` automatically follow the same penalized grade.

## Points to confirm

1. **Persisting the penalty in `raw_points`** — this matches your “change the first one and the second one follows” instruction. The trade-off is that the original teacher-entered points are no longer stored. If you prefer to keep the original and apply the penalty only when displaying/exporting, I’d need to also update `GradeReport` and `CsvExport` (or add more DB columns).
2. **Email** — should I include the penalty line and/or the original grade before penalty? I recommend adding the penalty line so students understand the lower grade.
3. **Existing graded demo data** — the already-graded rows in `data.sql` will keep their current values. Only new gradings through the endpoint get the penalty. Do you want a one-time backfill/update of the demo data too?

I haven’t modified any files yet. To apply this plan, you’ll need to switch to a coding agent.

> Demande:
>
> 1- Yes persist the penalty in raw_points and throw the original
> 
>2- Add the penalty line and the original grade but on the feedback column but not as an email sent
>
>3- No, do not update the already-graded rows
>
>Also a quick-remark: the deadline column in the exercise table is of a date type and the submission submitted_at is a timestamp so we will assume that the deadline is set on the day given at 00:00 and thus even submission on the same day but 2 hours late is considered as the first day late 


Reponse:

Got it. Revised plan:

## 1. New deadline semantics

The deadline is treated as `deadline.atStartOfDay()`.

```java
LocalDateTime deadlineStart = deadline.atStartOfDay();
long daysLate = 0;
if (submittedAt.isAfter(deadlineStart)) {
    daysLate = ChronoUnit.DAYS.between(deadline, submittedAt.toLocalDate()) + 1;
}
```

With a deadline of `2026-11-10`:

| Submitted at | Days late |
|---|---:|
| `2026-11-09 23:59` | 0 |
| `2026-11-10 00:00` | 0 |
| `2026-11-10 02:00` | 1 |
| `2026-11-10 23:59` | 1 |
| `2026-11-11 00:00` | 2 |
| `2026-11-12 10:00` | 3 |

So the deadline day itself counts as the first late day once `00:00` has passed, as you described.

## 2. Penalty calculation

- `penaltyRate = min(daysLate × 0.10, 0.50)`
- `penalizedRaw = initialPoints × (1 − penaltyRate)`, rounded to 2 decimals
- `grade = penalizedRaw / maxPoints × 20`, rounded to 1 decimal
- The 50% cap is reached on the 5th late day and stays at 50% afterward.

Using BigDecimal for the intermediate calculation, so `grade()` and `get()` produce exactly the same result.

## 3. `grade()` changes

- Validate the teacher’s initial points against `max_points` first.
- Compute `daysLate`, `penaltyRate`, `penalizedRaw`, and final `grade`.
- Persist **only the penalized value** in `raw_points`. The original raw points are discarded.
- Build the stored feedback:
  - Keep the teacher’s feedback.
  - If `daysLate > 0`, append:
    ```
    Pénalité de retard : 20 % (2 jours de retard).
    Note avant pénalité : 10.0/20.
    ```
  - Store the combined text in the `feedback` column.
- The existing grade email stays as it is and uses only the teacher’s original feedback. The penalty note is **not** added to the email.
- Response will contain:
  - `rawPoints` → penalized raw value
  - `maxPoints`
  - `late`
  - `lateDays`
  - `penaltyPercent`
  - `grade` → penalized grade
  - `feedback` → stored feedback including the penalty note
  - no `initialPoints`, since the original is discarded.

## 4. `submit()` and `get()` consistency

- `submit()` will use the same `lateDays` helper, so a submission after `00:00` on the deadline day is correctly reported as late.
- `get()` will use the same helper and return `lateDays` and `penaltyPercent` alongside the existing fields.
- `get()` computes the grade from the stored penalized `raw_points`, so it matches the grade endpoint.

## 5. What stays untouched

- No schema change.
- No backfill of already-graded rows.
- `GradeReport` and `CsvExport` grades automatically follow because they read `raw_points`.
- Their separate `late` boolean still uses the old date-only comparison. I can align that too, but it’s outside `SubmissionController.java`.

I haven’t modified any files. To implement this, switch to a coding agent.

> Utilisateur:
>
> Go ahead!

Reponse:

Implemented. Here’s what changed:

### `src/main/java/hei/school/SubmissionController.java`

- Added the late-day calculation based on the deadline being at `00:00` on the deadline date:
  - `2026-11-10 00:00` → 0 days late
  - `2026-11-10 02:00` → 1 day late
  - `2026-11-11 00:00` → 2 days late
- Added the penalty:
  - `10%` per late day, capped at `50%`
  - computed from the initial points, not the penalized value
- `grade()` now:
  - stores only the penalized value in `raw_points`
  - computes the final grade from that penalized value
  - appends this to `feedback` when late:
    ```
    Pénalité de retard : 20 % (2 jours de retard).
    Note avant pénalité : 10.0/20.
    ```
  - keeps the existing grade email behavior and does **not** add the penalty note to the email
  - returns `lateDays` and `penaltyPercent`
- `submit()` and `get()` now use the same late-day logic and expose `lateDays` / `penaltyPercent`.
- Re-grading removes the previous generated penalty note before adding the new one, avoiding duplicates.

### `README.md`

Updated the business rules to document the new deadline and penalty behavior.

### Verification

- `mvn -q -DskipTests compile` passes.
- I also verified the date and penalty calculations independently: `50/100` gives `45`, `40`, `25`, `25` for 1, 2, 5, and 10 days late.

One note: `GradeReport` and `CsvExport` read the penalized `raw_points`, so their grades automatically reflect the penalty. Their separate `late` boolean still uses the old date-only comparison; I left those files untouched since the request targeted `SubmissionController.java`, but I can align them too if you want.


### Bilan

- Fichier Modifie: Submission Controller (Impl du nouveau systeme de penalite) & README (Documentation du systeme de penalite)
- Futur TODO: remplacer les methodes "helpers" statiques en methode d'une classee dediee (Un service par exemple)