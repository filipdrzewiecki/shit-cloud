-- History rows written when a builder puts an earlier state of a document back (the state it replaced).
alter table doc_history drop constraint doc_history_op_check;
alter table doc_history add constraint doc_history_op_check
    check (op in ('update', 'delete', 'migrate', 'undo', 'restore'));
