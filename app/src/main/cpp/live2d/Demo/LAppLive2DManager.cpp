/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "LAppLive2DManager.hpp"
#include <string.h>
#include <stdlib.h>
#include <GLES2/gl2.h>
#include <Rendering/CubismRenderer.hpp>
#include <Rendering/OpenGL/CubismOffscreenManager_OpenGLES2.hpp>
#include <Motion/CubismMotionQueueManager.hpp>
#include "LAppPal.hpp"
#include "LAppDefine.hpp"
#include "LAppDelegate.hpp"
#include "LAppModel.hpp"
#include "LAppView.hpp"
#include "JniBridgeC.hpp"

using namespace Csm;
using namespace LAppDefine;

namespace {
    LAppLive2DManager* s_instance = NULL;

    void BeganMotion(ACubismMotion* self)
    {
        LAppPal::PrintLogLn("Motion Began: %x", self);
    }

    void FinishedMotion(ACubismMotion* self)
    {
        LAppPal::PrintLogLn("Motion Finished: %x", self);
    }

    int CompareCsmString(const void* a, const void* b)
    {
        return strcmp(reinterpret_cast<const Csm::csmString*>(a)->GetRawString(),
            reinterpret_cast<const Csm::csmString*>(b)->GetRawString());
    }
}

LAppLive2DManager* LAppLive2DManager::GetInstance()
{
    if (s_instance == NULL)
    {
        s_instance = new LAppLive2DManager();
    }

    return s_instance;
}

void LAppLive2DManager::ReleaseInstance()
{
    if (s_instance != NULL)
    {
        delete s_instance;
    }

    s_instance = NULL;
}

LAppLive2DManager::LAppLive2DManager()
    : _viewMatrix(NULL)
    , _lastExpressionTime(0.0f)
    , _nextExpressionInterval(25.0f)
    , _expressionTimerInitialized(false)
{
    _viewMatrix = new CubismMatrix44();
    SetUpModel();

    ChangeScene(LAppDelegate::GetInstance()->GetSceneIndex());
}

LAppLive2DManager::~LAppLive2DManager()
{
    ReleaseAllModel();
    delete _viewMatrix;
    Csm::Rendering::CubismOffscreenManager_OpenGLES2::ReleaseInstance();
}

void LAppLive2DManager::ReleaseAllModel()
{
    for (csmUint32 i = 0; i < _models.GetSize(); i++)
    {
        delete _models[i];
    }

    _models.Clear();
}

void LAppLive2DManager::SetUpModel()
{
    // ★ 增强：查找目录下任意 .model3.json 文件（不要求文件名前缀与目录名一致）
    // 这样自定义导入的模型（如 浣熊公皮.model3.json 放在 小浣熊/ 目录下）也能正确加载
    const char MODEL3_JSON_SUFFIX[] = u8".model3.json";
    const size_t SUFFIX_LEN = sizeof(MODEL3_JSON_SUFFIX) - 1;
    _modelDir.Clear();
    _model3JsonName.Clear();
    Csm::csmVector<Csm::csmString> root = JniBridgeC::GetAssetList("live2d");
    for (size_t i = 0; i < root.GetSize(); i++)
    {
        Csm::csmString subDir(ResourcesPath);
        subDir += root[i];
        Csm::csmVector<Csm::csmString> sub = JniBridgeC::GetAssetList(subDir.GetRawString());
        // ★ 遍历目录下所有文件，找任意 .model3.json 文件
        Csm::csmString foundModel3Json = "";
        for (size_t j = 0; j < sub.GetSize(); j++)
        {
            const char* name = sub[j].GetRawString();
            size_t nameLen = strlen(name);
            if (nameLen >= SUFFIX_LEN &&
                strcmp(name + nameLen - SUFFIX_LEN, MODEL3_JSON_SUFFIX) == 0)
            {
                foundModel3Json = sub[j];
                if (DebugLogEnable)
                {
                    LAppPal::PrintLogLn("[APP]found model: dir=%s json=%s", root[i].GetRawString(), name);
                }
                break;
            }
        }
        if (strlen(foundModel3Json.GetRawString()) > 0)
        {
            _modelDir.PushBack(root[i]);
            _model3JsonName.PushBack(foundModel3Json);
        }
    }
    // ★ 同步冒泡排序 _modelDir 和 _model3JsonName（不能用 qsort 因为两个数组要同步）
    for (size_t i = 0; i < _modelDir.GetSize(); i++)
    {
        for (size_t j = i + 1; j < _modelDir.GetSize(); j++)
        {
            if (strcmp(_modelDir[i].GetRawString(), _modelDir[j].GetRawString()) > 0)
            {
                Csm::csmString tmpDir = _modelDir[i];
                _modelDir[i] = _modelDir[j];
                _modelDir[j] = tmpDir;
                Csm::csmString tmpJson = _model3JsonName[i];
                _model3JsonName[i] = _model3JsonName[j];
                _model3JsonName[j] = tmpJson;
            }
        }
    }
}

LAppModel* LAppLive2DManager::GetModel(csmUint32 no) const
{
    if (no < _models.GetSize())
    {
        return _models[no];
    }

    return NULL;
}

void LAppLive2DManager::SetRenderTargetSize(csmUint32 width, csmUint32 height)
{
    for (csmUint32 i = 0; i < _models.GetSize(); i++)
    {
        LAppModel* model = GetModel(i);

        model->SetRenderTargetSize(width, height);
    }
}

void LAppLive2DManager::OnDrag(csmFloat32 x, csmFloat32 y) const
{
    for (csmUint32 i = 0; i < _models.GetSize(); i++)
    {
        LAppModel* model = GetModel(i);

        model->SetDragging(x, y);
    }
}

void LAppLive2DManager::OnTap(csmFloat32 x, csmFloat32 y)
{
    int width = LAppDelegate::GetInstance()->GetWindowWidth();
    int height = LAppDelegate::GetInstance()->GetWindowHeight();
    float aspectRatio = static_cast<float>(width) / static_cast<float>(height);
    float displayRatio = static_cast<float>(height) / static_cast<float>(width);

    if (DebugLogEnable)
    {
        LAppPal::PrintLogLn("[APP]tap point: {x:%.2f y:%.2f}", x, y);
    }

    for (csmUint32 i = 0; i < _models.GetSize(); i++)
    {
        LAppModel* model = GetModel(i);
        float canvasRatio = model->GetModel()->GetCanvasHeight() / model->GetModel()->GetCanvasWidth();

        csmFloat32 adjustedX = x;
        csmFloat32 adjustedY = y;

        if (canvasRatio < displayRatio)
        {
            // OnUpdateでのプロジェクションスケールを打ち消してモデル座標系に変換
            adjustedX = x / aspectRatio;
            adjustedY = y / aspectRatio;
        }

        // ★ 修复：无论是否命中 HitArea，都触发随机动作（让"小爱弥斯"等无 HitArea 模型也能响应点击）
        // 优先尝试点击身体动作；如果模型没有 Tap@Body 动作，则触发随机表情
        if (model->HitTest(HitAreaNameBody, adjustedX, adjustedY))
        {
            if (DebugLogEnable)
            {
                LAppPal::PrintLogLn("[APP]hit area: [%s]", HitAreaNameBody);
            }
            model->StartRandomMotion(MotionGroupTapBody, PriorityNormal, FinishedMotion, BeganMotion);
        }
        else
        {
            // ★ 未命中 HitArea 时也触发动作（用户要求：单点一下就触发）
            // StartRandomMotion 内部会检查动作数量，若为 0 返回 InvalidMotionQueueEntryHandleValue
            CubismMotionQueueEntryHandle handle = model->StartRandomMotion(MotionGroupTapBody, PriorityNormal, FinishedMotion, BeganMotion);
            if (handle == InvalidMotionQueueEntryHandleValue)
            {
                // 模型没有 Tap@Body 动作时，触发随机表情
                if (DebugLogEnable)
                {
                    LAppPal::PrintLogLn("[APP]tap anywhere -> random expression (no Tap@Body motion)");
                }
                model->SetRandomExpression();
            }
            else
            {
                if (DebugLogEnable)
                {
                    LAppPal::PrintLogLn("[APP]tap anywhere -> random motion (no hit area)");
                }
            }
        }
    }
}

void LAppLive2DManager::OnUpdate()
{
    int width = LAppDelegate::GetInstance()->GetWindowWidth();
    int height = LAppDelegate::GetInstance()->GetWindowHeight();
    float aspectRatio = static_cast<float>(width) / static_cast<float>(height);
    float displayRatio = static_cast<float>(height) / static_cast<float>(width);

    // モデルで使用するオフスクリーン管理の開始処理
    Csm::Rendering::CubismOffscreenManager_OpenGLES2::GetInstance()->BeginFrameProcess();

    // ★ 表情定时器（新默认模型"小爱弥斯"有自己的表情系统，不强制触发特定表情）
    if (_models.GetSize() > 0)
    {
        LAppModel* modelForExpr = GetModel(0);
        if (modelForExpr != NULL && modelForExpr->GetModel() != NULL)
        {
            if (!_expressionTimerInitialized)
            {
                _expressionTimerInitialized = true;
                _lastExpressionTime = 0.0f;
                _nextExpressionInterval = 999999.0f;
                LAppPal::PrintLogLn("[APP]model loaded, expression timer initialized");
            }
        }
    }

    csmUint32 modelCount = _models.GetSize();
    for (csmUint32 i = 0; i < modelCount; ++i)
    {
        CubismMatrix44 projection;
        LAppModel* model = GetModel(i);

        if (model->GetModel() == NULL)
        {
            LAppPal::PrintLogLn("Failed to model->GetModel().");
            continue;
        }

        float canvasRatio = model->GetModel()->GetCanvasHeight() / model->GetModel()->GetCanvasWidth();

        if (canvasRatio < displayRatio)
        {
            // 横長モデルを幅に合わせて縦方向のスケールを調整
            model->GetModelMatrix()->SetWidth(2.0f);
            projection.Scale(1.0f, aspectRatio);
        }
        else
        {
            // 縦長モデルを高さに合わせて横方向のスケールを調整
            model->GetModelMatrix()->SetHeight(2.0f);
            projection.Scale(1.0f / aspectRatio, 1.0f);
        }

        // 必要があればここで乗算
        if (_viewMatrix != NULL)
        {
            projection.MultiplyByMatrix(_viewMatrix);
        }

        // モデル1体描画前コール
        LAppDelegate::GetInstance()->GetView()->PreModelDraw(*model);

        model->Update();
        model->Draw(projection);///< 参照渡しなのでprojectionは変質する

        // モデル1体描画後コール
        LAppDelegate::GetInstance()->GetView()->PostModelDraw(*model);
    }

    // モデルで使用するオフスクリーン管理の終了処理
    Csm::Rendering::CubismOffscreenManager_OpenGLES2::GetInstance()->EndFrameProcess();
    // もし余っているオフスクリーンのリソースを解放したい場合行う処理
    Csm::Rendering::CubismOffscreenManager_OpenGLES2::GetInstance()->ReleaseStaleRenderTextures();
}

void LAppLive2DManager::NextScene()
{
    csmInt32 no = (LAppDelegate::GetInstance()->GetSceneIndex() + 1) % _modelDir.GetSize();
    ChangeScene(no);
}

void LAppLive2DManager::ChangeScene(Csm::csmInt32 index)
{
    LAppDelegate::GetInstance()->SetSceneIndex(index);
    if (DebugLogEnable)
    {
        LAppPal::PrintLogLn("[APP]model index: %d", index);
    }

    // ★ 边界检查：避免 _modelDir 为空时越界访问
    if (_modelDir.GetSize() == 0)
    {
        LAppPal::PrintLogLn("[APP]ChangeScene: no model found in live2d directory!");
        return;
    }
    if (index < 0 || (csmUint32)index >= _modelDir.GetSize())
    {
        LAppPal::PrintLogLn("[APP]ChangeScene: index out of range, fallback to 0");
        index = 0;
    }

    // ★ 使用保存的 model3.json 文件名（不要求与目录名一致）
    csmString modelPath(ResourcesPath);
    modelPath += _modelDir[index] + "/";
    csmString modelJsonName = _model3JsonName[index];

    ReleaseAllModel();
    _models.PushBack(new LAppModel());
    _models[0]->LoadAssets(modelPath.GetRawString(), modelJsonName.GetRawString());

    // ★ 修复闪退：LoadAssets 失败时（_model 为 NULL，例如 model3.json 解析失败、moc3 加载失败）
    // 必须从 _models 移除并 delete，否则后续 OnSurfaceCreate 的 ReloadRenderer、每帧 Update/Draw
    // 都会因 _model==NULL 解引用崩溃
    if (_models[0]->GetModel() == NULL)
    {
        LAppPal::PrintLogLn("[APP]ChangeScene: LoadAssets failed for %s, removing invalid model", modelJsonName.GetRawString());
        delete _models[0];
        _models.Clear();
        return;
    }

    /*
     * モデル半透明表示を行うサンプルを提示する。
     * ここでUSE_RENDER_TARGET、USE_MODEL_RENDER_TARGETが定義されている場合
     * 別のレンダリングターゲットにモデルを描画し、描画結果をテクスチャとして別のスプライトに張り付ける。
     */
    {
#if defined(USE_RENDER_TARGET)
        // LAppViewの持つターゲットに描画を行う場合、こちらを選択
        LAppView::SelectTarget useRenderTarget = LAppView::SelectTarget_ViewFrameBuffer;
#elif defined(USE_MODEL_RENDER_TARGET)
        // 各LAppModelの持つターゲットに描画を行う場合、こちらを選択
        LAppView::SelectTarget useRenderTarget = LAppView::SelectTarget_ModelFrameBuffer;
#else
        // デフォルトのメインフレームバッファへレンダリングする(通常)
        LAppView::SelectTarget useRenderTarget = LAppView::SelectTarget_None;
#endif

#if defined(USE_RENDER_TARGET) || defined(USE_MODEL_RENDER_TARGET)
        // モデル個別にαを付けるサンプルとして、もう1体モデルを作成し、少し位置をずらす
        _models.PushBack(new LAppModel());
        _models[1]->LoadAssets(modelPath.GetRawString(), modelJsonName.GetRawString());
        _models[1]->GetModelMatrix()->TranslateX(0.2f);
#endif

        LAppDelegate::GetInstance()->GetView()->SwitchRenderingTarget(useRenderTarget);

        // 別レンダリング先を選択した際の背景クリア色
        float clearColor[3] = { 0.0f, 0.0f, 0.0f };
        LAppDelegate::GetInstance()->GetView()->SetRenderTargetClearColor(clearColor[0], clearColor[1], clearColor[2]);
    }
}

csmUint32 LAppLive2DManager::GetModelNum() const
{
    return _models.GetSize();
}

void LAppLive2DManager::SetViewMatrix(CubismMatrix44* m)
{
    for (int i = 0; i < 16; i++) {
        _viewMatrix->GetArray()[i] = m->GetArray()[i];
    }
}

// ★ 获取当前模型所有表情名称（用 \n 分隔）
csmString LAppLive2DManager::GetExpressionNames()
{
    if (_models.GetSize() == 0) return "";
    LAppModel* model = GetModel(0);
    if (model == NULL || model->GetModel() == NULL) return "";
    return model->GetExpressionNames();
}

// ★ 触发指定表情
void LAppLive2DManager::TriggerExpression(const csmChar* name)
{
    if (_models.GetSize() == 0 || name == NULL || strlen(name) == 0) return;
    LAppModel* model = GetModel(0);
    if (model == NULL || model->GetModel() == NULL) return;
    model->SetExpression(name);
    LAppPal::PrintLogLn("[APP]TriggerExpression: %s", name);
}

// ★★★ 设置视角偏移（陀螺仪用）
// x, y 范围 [-1, 1]，通过 SetDragging 让 CubismLookUpdater 驱动眼睛/头部视角
void LAppLive2DManager::SetViewOffset(csmFloat32 x, csmFloat32 y)
{
    if (_models.GetSize() == 0) return;
    // 限制范围 [-1, 1]
    if (x < -1.0f) x = -1.0f;
    if (x > 1.0f) x = 1.0f;
    if (y < -1.0f) y = -1.0f;
    if (y > 1.0f) y = 1.0f;
    for (csmUint32 i = 0; i < _models.GetSize(); i++)
    {
        LAppModel* model = GetModel(i);
        if (model == NULL || model->GetModel() == NULL) continue;
        model->SetDragging(x, y);
    }
}

