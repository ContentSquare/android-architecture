/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.architecture.blueprints.todoapp.data.source.local;

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import com.example.android.architecture.blueprints.todoapp.data.Task;
import com.example.android.architecture.blueprints.todoapp.data.source.TasksDataSource;
import com.example.android.architecture.blueprints.todoapp.data.source.local.TasksPersistenceContract.TaskEntry;
import com.example.android.architecture.blueprints.todoapp.util.schedulers.BaseSchedulerProvider;
import com.squareup.sqlbrite.BriteDatabase;
import com.squareup.sqlbrite.SqlBrite;

import java.util.List;

import rx.Completable;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;


/**
 * Concrete implementation of a data source as a db.
 */
public class TasksLocalDataSource implements TasksDataSource {

    @Nullable
    private static TasksLocalDataSource INSTANCE;

    @NonNull
    private final BriteDatabase mDatabaseHelper;

    @NonNull
    private Func1<Cursor, Task> mTaskMapperFunction;

    // Prevent direct instantiation.
    private TasksLocalDataSource(@NonNull Context context,
                                 @NonNull BaseSchedulerProvider schedulerProvider) {
        checkNotNull(context, "context cannot be null");
        checkNotNull(schedulerProvider, "scheduleProvider cannot be null");
        TasksDbHelper dbHelper = new TasksDbHelper(context);
        SqlBrite sqlBrite = new SqlBrite.Builder().build();
        mDatabaseHelper = sqlBrite.wrapDatabaseHelper(dbHelper, schedulerProvider.io());
        mTaskMapperFunction = new Func1<Cursor, Task>() {
            @Override
            public Task call(Cursor cursor) {
                return getTask(cursor);
            }
        };
    }

    public static TasksLocalDataSource getInstance(
            @NonNull Context context,
            @NonNull BaseSchedulerProvider schedulerProvider) {
        if (INSTANCE == null) {
            INSTANCE = new TasksLocalDataSource(context, schedulerProvider);
        }
        return INSTANCE;
    }

    public static void destroyInstance() {
        INSTANCE = null;
    }

    @NonNull
    private Task getTask(@NonNull Cursor c) {
        String itemId = c.getString(c.getColumnIndexOrThrow(TaskEntry.COLUMN_NAME_ENTRY_ID));
        String title = c.getString(c.getColumnIndexOrThrow(TaskEntry.COLUMN_NAME_TITLE));
        String description =
                c.getString(c.getColumnIndexOrThrow(TaskEntry.COLUMN_NAME_DESCRIPTION));
        boolean completed =
                c.getInt(c.getColumnIndexOrThrow(TaskEntry.COLUMN_NAME_COMPLETED)) == 1;
        return new Task(title, description, itemId, completed);
    }

    /**
     * @return an Observable that emits the list of tasks in the database, every time the Tasks
     * table is modified
     */
    @Override
    public Observable<List<Task>> getTasks() {
        String[] projection = {
                TaskEntry.COLUMN_NAME_ENTRY_ID,
                TaskEntry.COLUMN_NAME_TITLE,
                TaskEntry.COLUMN_NAME_DESCRIPTION,
                TaskEntry.COLUMN_NAME_COMPLETED
        };
        String sql = String.format("SELECT %s FROM %s", TextUtils.join(",", projection), TaskEntry.TABLE_NAME);
        return mDatabaseHelper.createQuery(TaskEntry.TABLE_NAME, sql)
                .mapToList(mTaskMapperFunction);
    }

    @Override
    public Observable<Task> getTask(@NonNull String taskId) {
        String[] projection = {
                TaskEntry.COLUMN_NAME_ENTRY_ID,
                TaskEntry.COLUMN_NAME_TITLE,
                TaskEntry.COLUMN_NAME_DESCRIPTION,
                TaskEntry.COLUMN_NAME_COMPLETED
        };
        String sql = String.format("SELECT %s FROM %s WHERE %s LIKE ?",
                TextUtils.join(",", projection), TaskEntry.TABLE_NAME, TaskEntry.COLUMN_NAME_ENTRY_ID);
        return mDatabaseHelper.createQuery(TaskEntry.TABLE_NAME, sql, taskId)
                .mapToOneOrDefault(mTaskMapperFunction, null);
    }

    @Override
    public Completable saveTask(@NonNull final Task task) {
        checkNotNull(task);
        return Completable.fromAction(new Action0() {
            @Override
            public void call() {
                ContentValues values = toContentValues(task);
                mDatabaseHelper.insert(TaskEntry.TABLE_NAME, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
        });
    }

    @Override
    public Completable saveTasks(@NonNull final List<Task> tasks) {
        checkNotNull(tasks);

        return Completable.using(new Func0<BriteDatabase.Transaction>() {
            @Override
            public BriteDatabase.Transaction call() {
                return mDatabaseHelper.newTransaction();
            }
        }, new Func1<BriteDatabase.Transaction, Completable>() {
            @Override
            public Completable call(BriteDatabase.Transaction transaction) {
                return inTransactionInsert(tasks, transaction).toCompletable();
            }
        }, new Action1<BriteDatabase.Transaction>() {
            @Override
            public void call(BriteDatabase.Transaction transaction) {
                transaction.end();
            }
        });
    }

    @NonNull
    private Observable<List<Task>> inTransactionInsert(@NonNull List<Task> tasks,
                                                       @NonNull final BriteDatabase.Transaction transaction) {
        checkNotNull(tasks);
        checkNotNull(transaction);

        return Observable.from(tasks)
                .doOnNext(new Action1<Task>() {
                    @Override
                    public void call(Task task) {
                        ContentValues values = toContentValues(task);
                        mDatabaseHelper.insert(TaskEntry.TABLE_NAME, values);
                    }
                })
                .doOnCompleted(new Action0() {
                    @Override
                    public void call() {
                        transaction.markSuccessful();
                    }
                })
                .toList();
    }

    private ContentValues toContentValues(Task task) {
        ContentValues values = new ContentValues();
        values.put(TaskEntry.COLUMN_NAME_ENTRY_ID, task.getId());
        values.put(TaskEntry.COLUMN_NAME_TITLE, task.getTitle());
        values.put(TaskEntry.COLUMN_NAME_DESCRIPTION, task.getDescription());
        values.put(TaskEntry.COLUMN_NAME_COMPLETED, task.isCompleted());
        return values;
    }

    @Override
    public Completable completeTask(@NonNull Task task) {
        checkNotNull(task);
        return completeTask(task.getId());
    }

    @Override
    public Completable completeTask(@NonNull final String taskId) {
        return Completable.fromAction(new Action0() {
            @Override
            public void call() {
                ContentValues values = new ContentValues();
                values.put(TaskEntry.COLUMN_NAME_COMPLETED, true);

                String selection = TaskEntry.COLUMN_NAME_ENTRY_ID + " LIKE ?";
                String[] selectionArgs = {taskId};
                mDatabaseHelper.update(TaskEntry.TABLE_NAME, values, selection, selectionArgs);
            }
        });
    }

    @Override
    public Completable activateTask(@NonNull Task task) {
        return activateTask(task.getId());
    }

    @Override
    public Completable activateTask(@NonNull final String taskId) {
        return Completable.fromAction(new Action0() {
            @Override
            public void call() {
                ContentValues values = new ContentValues();
                values.put(TaskEntry.COLUMN_NAME_COMPLETED, false);

                String selection = TaskEntry.COLUMN_NAME_ENTRY_ID + " LIKE ?";
                String[] selectionArgs = {taskId};
                mDatabaseHelper.update(TaskEntry.TABLE_NAME, values, selection, selectionArgs);
            }
        });
    }

    @Override
    public void clearCompletedTasks() {
        String selection = TaskEntry.COLUMN_NAME_COMPLETED + " LIKE ?";
        String[] selectionArgs = {"1"};
        mDatabaseHelper.delete(TaskEntry.TABLE_NAME, selection, selectionArgs);
    }

    @Override
    public Completable refreshTasks() {
        // Not required because the {@link TasksRepository} handles the logic of refreshing the
        // tasks from all the available data sources.
        return Completable.complete();
    }

    @Override
    public void deleteAllTasks() {
        mDatabaseHelper.delete(TaskEntry.TABLE_NAME, null);
    }

    @Override
    public void deleteTask(@NonNull String taskId) {
        String selection = TaskEntry.COLUMN_NAME_ENTRY_ID + " LIKE ?";
        String[] selectionArgs = {taskId};
        mDatabaseHelper.delete(TaskEntry.TABLE_NAME, selection, selectionArgs);
    }
}
