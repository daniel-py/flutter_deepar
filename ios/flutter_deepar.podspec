Pod::Spec.new do |s|
  s.name             = 'flutter_deepar'
  s.version          = '0.1.0'
  s.summary          = 'Flutter plugin for DeepAR augmented reality SDK'
  s.description      = <<-DESC
  A Flutter plugin providing native integration with the DeepAR augmented reality SDK.
  Includes camera capture, AR effect loading, and processed frame output via streams.
                       DESC
  s.homepage         = 'https://github.com/daniel-py/flutter_deepar'
  s.license          = { :type => 'MIT', :file => '../LICENSE' }
  s.author           = { 'daniel-py' => 'your-email@example.com' }
  s.source           = { :git => 'https://github.com/daniel-py/flutter_deepar.git', :tag => s.version.to_s }

  s.ios.deployment_target = '13.0'
  s.swift_version = '5.0'

  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'
  s.dependency 'DeepAR'

  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
end
