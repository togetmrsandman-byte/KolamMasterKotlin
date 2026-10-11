ALTER TABLE public.user_data
    ADD COLUMN visible_lesson_count integer NOT NULL DEFAULT 60;

CREATE OR REPLACE FUNCTION public.keep_visible_lesson_count_monotonic()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.visible_lesson_count :=
        GREATEST(OLD.visible_lesson_count, NEW.visible_lesson_count);
    RETURN NEW;
END;
$$;

CREATE TRIGGER user_data_keep_visible_lesson_count_monotonic
BEFORE UPDATE OF visible_lesson_count ON public.user_data
FOR EACH ROW
EXECUTE FUNCTION public.keep_visible_lesson_count_monotonic();
