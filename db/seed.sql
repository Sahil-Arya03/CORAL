-- CreatorOS / Coral — seed data.
-- Mirrors the original in-memory CoralClient fixtures so chat + timeline behave
-- identically once backed by the real database. Times are relative to now() so
-- the "due soon / today" logic and the frontend's today-filter stay meaningful.
-- Safe to re-run: truncates then re-inserts.

TRUNCATE github.commits, github.pull_requests, notion.tasks, calendar.events, gmail.emails;

-- notion.tasks
INSERT INTO notion.tasks (id, title, status, priority, due_date, project, updated_at) VALUES
  ('t1', 'Finish DBMS assignment', 'todo',        'high',   now() - interval '1 day',  'DBMS', now() - interval '3 days'),
  ('t2', 'OS lab report',          'in_progress', 'medium', now() + interval '2 days',  'OS',   now() - interval '1 day'),
  ('t3', 'DSA practice set',       'todo',        'high',   now() + interval '1 day',   'DSA',  now() - interval '5 days');

-- github.commits
INSERT INTO github.commits (sha, repo, author, message, committed_at) VALUES
  ('a1b2', 'os-lab', 'arya', 'wip: scheduler',            now() - interval '2 days'),
  ('c3d4', 'dsa',    'arya', 'add two-pointer solutions', now() - interval '6 hours');

-- github.pull_requests
INSERT INTO github.pull_requests (id, repo, title, state, created_at, merged_at) VALUES
  ('pr1', 'os-lab', 'Scheduler v1', 'open', now() - interval '3 days', NULL);

-- calendar.events
INSERT INTO calendar.events (id, title, start_at, end_at, attendees_count, is_meeting) VALUES
  ('e1', 'DBMS lecture', now() + interval '4 hours', now() + interval '5 hours', 40, TRUE),
  ('e2', 'Project sync', now() + interval '1 day',   now() + interval '1 day' + interval '1 hour', 5, TRUE);

-- gmail.emails
INSERT INTO gmail.emails (id, subject, sender, snippet, is_unread, received_at, importance, is_archived) VALUES
  ('m1', 'DBMS assignment deadline moved to tomorrow', 'prof@univ.edu', 'Please submit by EOD tomorrow.', TRUE, now() - interval '4 hours', 'high', FALSE),
  ('m2', 'Weekly newsletter',                          'news@list.com', 'Top stories this week.',         TRUE, now() - interval '1 day',   'low',  FALSE);


-- ── CreatorOS app schema seed ────────────────────────────────────────
TRUNCATE creatoros.user_memory, creatoros.behavioral_patterns,
         creatoros.productivity_metrics, creatoros.daily_reflections,
         creatoros.ai_conversations, creatoros.action_logs,
         creatoros.query_logs, creatoros.users
         RESTART IDENTITY CASCADE;

INSERT INTO creatoros.users (email, display_name) VALUES
  ('arya@creatoros.dev', 'Alex');

-- Long-term AI memory (mirrors MemoryInjectionService's seeded observations).
INSERT INTO creatoros.user_memory (user_id, category, content, tags, importance, confidence, last_referenced_at)
SELECT u.id, v.category, v.content, v.tags, v.importance, v.confidence, now()
FROM creatoros.users u,
     (VALUES
        ('goal',       'User wants DSA consistency',                 ARRAY['coding','dsa','tasks'], 5::smallint, 0.9::real),
        ('habit',      'User is most productive after 8 PM',         ARRAY['overview','focus'],     4::smallint, 0.8::real),
        ('weakness',   'User frequently delays OS assignments',      ARRAY['tasks','os'],           4::smallint, 0.85::real),
        ('preference', 'User prefers concise, action-oriented answers', ARRAY['overview'],          3::smallint, 0.95::real)
     ) AS v(category, content, tags, importance, confidence)
WHERE u.email = 'arya@creatoros.dev';
