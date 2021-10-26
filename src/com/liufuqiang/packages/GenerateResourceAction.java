package com.liufuqiang.packages;

import com.google.common.base.Joiner;
import com.intellij.ide.dnd.aware.DnDAwareTree;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtilBase;
import org.apache.commons.lang3.StringUtils;
import org.apache.xmlbeans.impl.soap.MessageFactory;

import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @date 2021/10/21
 * @author liufuqiang
 */
public class GenerateResourceAction extends AnAction {

    private static final String PREFIX = "/";

    private static Clipboard clipboard = null;

    @Override
    public void actionPerformed(AnActionEvent event) {
        Editor editor = event.getData(PlatformDataKeys.EDITOR);
        PsiFile psiFile = PsiUtilBase.getPsiFileInEditor(editor, editor.getProject());
        String fileName = psiFile.getVirtualFile().getName();

        //只读文件直接返回
        if( psiFile.getFileType().isReadOnly()){
            NotificationUtils.notifyError(editor.getProject(), fileName + "为只读文件");
            return;
        }

        // 判断文件后缀是不是Controller
        String fileSuffix = "Controller.java";
        if (!fileName.endsWith(fileSuffix)) {
            NotificationUtils.notifyError(editor.getProject(), fileName + "文件没有接口");
            return;
        }

        String baseUrl = "";
        Document document = PsiDocumentManager.getInstance(event.getProject()).getDocument(psiFile);
        DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(fileName);
        for (PsiElement psiElement  : psiFile.getChildren()) {
            if (psiElement instanceof PsiClass){
                // 获取类上面的RequestMapping注解信息
                PsiClass psiClass = (PsiClass) psiElement;
                for (PsiAnnotation annotation : psiClass.getAnnotations()) {
                    if (StringUtils.equals(annotation.getQualifiedName(), "org.springframework.web.bind.annotation.RequestMapping")) {
                        baseUrl = annotation.findAttributeValue("value").getText().replaceAll("\"", "").trim();
                    }
                }

                if (StringUtils.isNotBlank(baseUrl) && !baseUrl.startsWith("/")) {
                    baseUrl = PREFIX.concat(baseUrl);
                }

                // 方法列表
                List<Map<String, String>> resourceList = new ArrayList<>(20);
                PsiMethod[] methods = psiClass.getMethods();
               for (PsiMethod method : methods) {
                   PsiAnnotation[] annotations = method.getAnnotations();
                   for (PsiAnnotation annotation : annotations) {
                       String qualifiedName = annotation.getQualifiedName();
                       if (!StringUtils.equals(qualifiedName, "org.springframework.web.bind.annotation.RequestMapping")
                       && !StringUtils.equals(qualifiedName, "org.springframework.web.bind.annotation.GetMapping")
                       && !StringUtils.equals(qualifiedName, "org.springframework.web.bind.annotation.PostMapping")
                       && !StringUtils.equals(qualifiedName, "org.springframework.web.bind.annotation.PutMapping")
                       && !StringUtils.equals(qualifiedName, "org.springframework.web.bind.annotation.DeleteMapping")) {
                           continue;
                       }

                       Map<String, String> params = new HashMap<>(3);
                       PsiAnnotationMemberValue annotationMemberValue = annotation.findAttributeValue("value");
                       String memberValue = annotationMemberValue.getText().replaceAll("\"", "").trim();
                       if (StringUtils.isNotBlank(memberValue) && !memberValue.startsWith("/")) {
                           memberValue = PREFIX.concat(memberValue);
                       }
                       String resourceUrl = baseUrl.concat(memberValue);

                       //  resource_url
                       params.put("resource_url", resourceUrl);

                       // resource_name
                       String resourceName = humpToUnderline(resourceUrl);
                       params.put("resource_name", resourceName);

                       // resource_desc
                       String resourceDesc = checkMethodComment(document, method);
                       params.put("resource_des", resourceDesc);

                       resourceList.add(params);
                       continue;
                   }
               }
               if (resourceList.size() == 0) {
                   NotificationUtils.notifyError(editor.getProject(), fileName + "文件没有接口");
                   return;
               }

               outputSqlInfo(editor, resourceList);
            }
        }
    }

    /**
     * 输出sql语句
     * @param editor
     * @param resourceList
     */
    public void outputSqlInfo(Editor editor, List<Map<String, String>> resourceList) {
        PsiFile psiFile = PsiUtilBase.getPsiFileInEditor(editor, editor.getProject());
        String fileName = psiFile.getVirtualFile().getName();
        StringBuilder sb = new StringBuilder();
        sb.append("-- sa_resource");
        sb.append("\n");
        sb.append("SET @parent_id = \"0\";\n");
        for (Map<String, String> param : resourceList) {
            String resourceSql = "INSERT INTO `sa_resource` (`id`, `p_id`, `resource_name`, `resource_des`, `resource_type`, `resource_url`, `curr_status`, `relation`, `company_id`, `create_user`, `create_time`,`update_user`, `update_time`, `status`) \n" +
                    "VALUES (CONCAT(UUID_SHORT(),''), @parent_id, '%s', '%s', NULL, '%s', NULL, NULL, '', NULL, NOW(), NULL, NOW(), NULL);\n";
            sb.append(String.format(resourceSql, param.get("resource_name"), param.get("resource_des"), param.get("resource_url")));
            sb.append("\n");
        }

        sb.append("-- sa_role_resource");
        sb.append("\n");
        sb.append("SET @role_id = \"需要替换为自己的role_id\";\n");
        sb.append("\n");
        for (Map<String, String> param : resourceList) {
            String resourceRoleSql = "INSERT INTO `sa_role_resource_map`(`id`, `role_id`, `resource_id`, `authority_flag`) VALUES\n" +
                    "(CONCAT(UUID_SHORT(),''), @role_id, (select id from sa_resource where resource_url = '%s'), '1';\n";
            sb.append(String.format(resourceRoleSql, param.get("resource_url")));
            sb.append("\n");
        }

        List<String> exceedList = resourceList.stream()
                .map(c -> c.get("resource_name"))
                .filter(f -> f.length() > 50).collect(Collectors.toList());
        String message = "云端脚本生成完成，共找到" + resourceList.size() + "个方法，";
        if (exceedList.size() == 0) {
            NotificationUtils.notifyInfo(editor.getProject(), message + "请替换@parent_id、@role_id");
        } else {
            NotificationUtils.notifyWarn(editor.getProject(), message + "资源名称过长，请保证长度小于50：" + Joiner.on("、").join(exceedList));
        }

        int result = Messages.showYesNoDialog(sb.toString(), fileName + "  sql", "一键复制", "取消", Messages.getInformationIcon());
        if (result == Messages.YES) {
            clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            StringSelection text = new StringSelection(sb.toString());
            clipboard.setContents(text, null);
            NotificationUtils.notifyInfo(editor.getProject(), "复制完成 ╮(￣▽￣)╭");
        }
    }

    /**
     * 小写转大写
     * @param var1
     * @return
     */
    public static String humpToUnderline(String var1) {
        StringBuilder result = new StringBuilder();
        if (var1 != null || var1.length() > 0) {
            result.append("RES_");
            result.append(var1.substring(0, 1).toUpperCase());

            for (int i = 1; i < var1.length(); i++) {
                String var2 = var1.substring(i, i + 1);
                // 在大写字母前添加下划线
                if (var2.equals(var2.toUpperCase()) && !Character.isDigit(var2.charAt(0))) {
                    result.append("_");
                }
                result.append(var2.toUpperCase());
            }
        }
        return result.toString().replaceAll("/", "");
    }

    /**
     * 获取注释
     * @param document
     * @param psiMethod
     * @return
     */
    private String  checkMethodComment(Document document, PsiMethod psiMethod){
        String comment = "";
        PsiComment classComment = null;
        for (PsiElement tmpEle : psiMethod.getChildren()) {
            if (tmpEle instanceof PsiComment){
                classComment = (PsiComment) tmpEle;
                // 注释的内容
                String tmpText = classComment.getText();

                String pattern = "[\\u4E00-\\u9FA5A-Za-z0-9]+";

                Pattern r = Pattern.compile(pattern);
                Matcher m = r.matcher(tmpText);
                while (m.find()) {
                    comment = m.group(0);
                    break;
                }
            }
        }
        return comment;
    }
}
