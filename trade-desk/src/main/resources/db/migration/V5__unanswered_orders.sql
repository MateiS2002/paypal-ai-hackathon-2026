-- S3: an accept that got no answer is UNANSWERED, which the owner can retry; FAILED is now final.
alter table approval drop constraint approval_order_status_check;
alter table approval add constraint approval_order_status_check
    check (order_status in ('NOT_SENT', 'SENDING', 'INVOICED', 'REFUSED', 'UNANSWERED', 'FAILED'));
