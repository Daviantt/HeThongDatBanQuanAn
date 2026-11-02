BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT 'app_lock' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.app_lock t
UNION ALL
SELECT 'restaurant_settings' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.restaurant_settings t
UNION ALL
SELECT 'group_promotion' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.group_promotion t
UNION ALL
SELECT 'app_user' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.app_user t
UNION ALL
SELECT 'dining_table' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.dining_table t
UNION ALL
SELECT 'app_migration' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.app_migration t
UNION ALL
SELECT 'table_combination' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.table_combination t
UNION ALL
SELECT 'menu_item' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.menu_item t
UNION ALL
SELECT 'reservation' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.reservation t
UNION ALL
SELECT 'reservation_table' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.reservation_table t
UNION ALL
SELECT 'order_line' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.order_line t
UNION ALL
SELECT 'change_request' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.change_request t
UNION ALL
SELECT 'audit_event' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.audit_event t
UNION ALL
SELECT 'payment_attempt' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.payment_attempt t
UNION ALL
SELECT 'demo_payment' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.demo_payment t
UNION ALL
SELECT 'takeaway_order' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.takeaway_order t
UNION ALL
SELECT 'takeaway_order_line' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.takeaway_order_line t
UNION ALL
SELECT 'takeaway_demo_payment' AS table_name, COUNT(*) AS row_count, md5(COALESCE(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text COLLATE "C"), '')) AS content_md5 FROM public.takeaway_demo_payment t
ORDER BY table_name;
COMMIT;
