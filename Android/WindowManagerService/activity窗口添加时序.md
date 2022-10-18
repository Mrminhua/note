```Java
note\Android\ActivityManagerService\code\android12\ActivityThread.java
public void handleResumeActivity(ActivityClientRecord r, boolean finalStateRequest, boolean isForward, String reason)

WindowManagerImpl.java
public void addView(@NonNull View view, @NonNull ViewGroup.LayoutParams params)

WindowManagerGlobal.java
public void addView(View view, ViewGroup.LayoutParams params, Display display, Window parentWindow, int userId)

ViewRootImpl.java
public void setView(View view, WindowManager.LayoutParams attrs, View panelParentView) 
public void setView(View view, WindowManager.LayoutParams attrs, View panelParentView, int userId) 

Session.java
public int addToDisplayAsUser(IWindow window, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, int userId, InsetsVisibilities requestedVisibilities,
            InputChannel outInputChannel, InsetsState outInsetsState,
            InsetsSourceControl[] outActiveControls)

WindowManagerService.java
public int addWindow(Session session, IWindow client, LayoutParams attrs, int viewVisibility,
            int displayId, int requestUserId, InsetsVisibilities requestedVisibilities,
            InputChannel outInputChannel, InsetsState outInsetsState,
            InsetsSourceControl[] outActiveControls)

```
